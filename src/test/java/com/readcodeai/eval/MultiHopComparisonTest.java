package com.readcodeai.eval;

import com.readcodeai.agent.AgentLoop;
import com.readcodeai.agent.AgentService;
import com.readcodeai.agent.AnswerService;
import com.readcodeai.agent.ScriptedLlmClient;
import com.readcodeai.agent.ToolRegistry;
import com.readcodeai.config.ReadCodeAiProperties;
import com.readcodeai.evidence.EvidenceVerifier;
import com.readcodeai.index.ProjectIndexer;
import com.readcodeai.retrieve.QueryRouter;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.RepoView;
import com.readcodeai.verify.TestCorpus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 第三组对比实验（离线版）：**单跳 vs 多跳，同一批链式问题，同一套口径**。
 *
 * <h3>这一版测的是什么、不测什么（必须说清）</h3>
 * 这里的"多跳"是**理想模型**：脚本按静态分析算出的 BFS 顺序逐跳追问。
 * 所以它给出的是**多跳机制的上限**（召回率应当到 100%，因为它走的就是真值那条路），
 * 它证明的是：量尺本身是对的、循环能按计划走完、每一跳的证据都落在调用链上。
 *
 * <p><b>它证明不了"真实模型也能做到"</b> —— 那是 {@code MultiHopLiveTest} 的活，
 * 两个数字必须分开报，不能拿这一版的 100% 去说效果。
 *
 * <p>对照组的定义见 {@link ChainExperiment}：单跳最强 = 对同一目标问"谁调用了它"
 * （确定性一跳查询），那是单跳能力对同一目标的上限。
 */
@SpringBootTest
class MultiHopComparisonTest {

    private static final long SEED = 20260918L;

    private static final int QUESTION_COUNT = 3;

    private static final int DEPTH = 2;

    @Autowired
    private AnswerService answerService;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private EvidenceVerifier evidenceVerifier;

    @Autowired
    private QueryRouter queryRouter;

    @Autowired
    private SymbolQueryService queries;

    @Autowired
    private ProjectIndexer indexer;

    @Autowired
    private ChainQuestionGenerator generator;

    @Autowired
    private ReadCodeAiProperties properties;

    @Test
    void measuresSingleHopAgainstMultiHopOnChainQuestions() {
        RepoView repo = corpus();
        // 默认预算是 3 轮（面向"一次问答"），而这里要走完 1 起步跳 + N 个上游 + 1 收尾
        // 给足预算才谈得上"多跳的完整度" —— 否则量到的是预算上限，不是能力上限
        properties.getLlm().setMaxRounds(12);
        List<ChainQuestionGenerator.ChainQuestion> questions = smallChains(repo.id(), QUESTION_COUNT);
        assumeTrue(questions.size() >= 2, "语料里没有足够的链式问题，跳过");

        // 多跳侧用「理想模型」脚本：从问题指向的符号起步，按 BFS 顺序往上追
        List<ChainExperiment.Row> rows = new ArrayList<>();
        for (ChainQuestionGenerator.ChainQuestion question : questions) {
            rows.add(new ChainExperiment(productOf(question), generator).run(repo.id(), question));
        }
        ChainExperiment.Summary summary = ChainExperiment.summarize(rows);

        // **诊断先打、断言后置**（此前踩过：断言一挂，样例就再也看不到了）
        System.out.println(System.lineSeparator() + "=== 第三组对比实验（离线 · 理想模型）===");
        System.out.print(ChainExperiment.table(rows));
        System.out.println(summary.toReport());

        for (ChainExperiment.Row row : rows) {
            assertThat(row.multiHop().answered())
                    .as("理想模型脚本必须能走完全程并给出结论").isTrue();
            assertThat(row.multiHop().recall())
                    .as("多跳应当覆盖全部真值（它走的就是真值那条路 —— 这正是量尺自检）")
                    .isEqualTo(1.0);
            assertThat(row.singleHopBest().recall())
                    .as("单跳最强也只能拿到第一层，召回必然低于 100%%").isLessThan(1.0);
            assertThat(row.multiHop().recall())
                    .as("多跳的完整度必须高于单跳上限").isGreaterThan(row.singleHopBest().recall());
        }
        assertThat(summary.multiHopRecall()).isGreaterThan(summary.singleHopBestRecall());
    }

    /**
     * 造一个"理想模型"的 AgentService：脚本内容取决于本题的计划。
     *
     * <p>计划 = 从问题指向的符号起步（第 1 跳拿到第一层上游），
     * 再按 BFS 顺序逐跳上溯 —— 也就是**一个知道真值的模型会怎么查**。
     */
    private AgentService productOf(ChainQuestionGenerator.ChainQuestion question) {
        List<String> lines = new ArrayList<>();
        if (question != null) {
            lines.add(ScriptedLlmClient.callTool("findCallers", "symbol",
                    question.target().qualifiedName()));
            for (String name : generator.bfsPlan(question.target().id(), question.depth() - 1)) {
                lines.add(ScriptedLlmClient.callTool("findCallers", "symbol", name));
            }
            lines.add(ScriptedLlmClient.answer("上游调用链已查完", question.target().filePath(),
                    question.target().startLine(), question.target().endLine(), null));
        }
        return new AgentService(answerService,
                new AgentLoop(toolRegistry, evidenceVerifier, com.readcodeai.verify.TestCheckers.NONE,
                        ScriptedLlmClient.lines(lines.toArray(String[]::new)), 2),
                queryRouter, queries, ScriptedLlmClient.lines(lines.toArray(String[]::new)),
                new com.readcodeai.agent.cache.NoopAnswerCache("对比实验不用缓存（每轮都真跑）"), properties);
    }

    /**
     * 挑**小链路**的题：真值比直接调用者多、但规模可控。
     *
     * <p>为什么要挑小的：理想模型要逐跳走完，链路一大，这个测试就从"验证机制"变成"跑很久"。
     * 真实模型的实验（live）更必须挑小的 —— 那是在花 token 和时间。
     */
    private List<ChainQuestionGenerator.ChainQuestion> smallChains(long repoId, int count) {
        List<ChainQuestionGenerator.ChainQuestion> picked = new ArrayList<>();
        for (ChainQuestionGenerator.ChainQuestion question : generator.generate(repoId, SEED, 40, DEPTH)) {
            if (question.directCallers().size() <= 3 && question.truthSize() >= 2) {
                picked.add(question);
                if (picked.size() >= count) {
                    break;
                }
            }
        }
        return picked;
    }

    private RepoView corpus() {
        var resolved = TestCorpus.resolve(indexer, queries);
        assumeTrue(resolved.isPresent(), "语料 " + TestCorpus.SAMPLE + " 不存在，跳过");
        return resolved.get();
    }
}
