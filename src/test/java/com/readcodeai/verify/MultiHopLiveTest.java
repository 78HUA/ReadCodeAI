package com.readcodeai.verify;

import com.readcodeai.agent.AgentService;
import com.readcodeai.config.LlmClient;
import com.readcodeai.config.ReadCodeAiProperties;
import com.readcodeai.eval.ChainExperiment;
import com.readcodeai.eval.ChainQuestionGenerator;
import com.readcodeai.index.ProjectIndexer;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.RepoView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 第三组对比实验（**真实模型**）：单跳 vs 多跳，链式问题，静态真值判卷。
 *
 * <p>没配 Key 时自动跳过 —— 它产出的是**实测数字**（召回率、平均跳数、token、耗时），
 * 那些数字要写进验证记录；而机制本身已由离线测试覆盖，所以跳过它不会让质量失去保障。
 *
 * <p>运行前 {@code source notes/llm-env.sh}（该文件在 .gitignore 里）。
 *
 * <h3>为什么真值仍然是静态算出来的</h3>
 * 链式问题的标准答案 = 调用图反向 BFS 的结果，**不是人标的、也不是模型判的**。
 * 这是这套实验可信的前提：否则"多跳更完整"就成了无法证伪的话。
 *
 * <h3>断言的分寸</h3>
 * <b>硬断言只有一条</b>：每一题都必须在预算内终止（不失控、不抛异常）。
 * 召回率**只打印不断言** —— 真实模型的波动是事实，把它写成断言只会培养"改测试"的习惯；
 * 数字如实记录到验证记录里，比一个绿色勾更有用。
 */
@SpringBootTest
class MultiHopLiveTest {

    private static final long SEED = 20260918L;

    private static final int QUESTION_COUNT = 3;

    private static final int DEPTH = 2;

    /** 1 起步跳 + ≤3 个上游 + 1 收尾，留余量：默认的 3 轮是"单次问答"场景的值。 */
    private static final int ROUNDS = 8;

    @Autowired
    private AgentService agentService;

    @Autowired
    private ChainQuestionGenerator generator;

    @Autowired
    private SymbolQueryService queries;

    @Autowired
    private ProjectIndexer indexer;

    @Autowired
    private ReadCodeAiProperties properties;

    @Autowired
    private LlmClient llmClient;

    @Test
    void runsTheChainExperimentAgainstTheRealModelAndReportsTheNumbers() {
        assumeTrue(llmClient.available(), "未配置 LLM（readcodeai.llm.*），跳过真实多跳实验");
        RepoView repo = corpus();
        List<ChainQuestionGenerator.ChainQuestion> questions = smallChains(repo.id(), QUESTION_COUNT);
        assumeTrue(questions.size() >= 2, "语料里没有足够的链式问题，跳过");
        properties.getLlm().setMaxRounds(ROUNDS);

        List<ChainExperiment.Row> rows = new ArrayList<>();
        for (ChainQuestionGenerator.ChainQuestion question : questions) {
            rows.add(new ChainExperiment(agentService, generator).run(repo.id(), question));
        }
        ChainExperiment.Summary summary = ChainExperiment.summarize(rows);

        System.out.println(System.lineSeparator() + "=== 第三组对比实验（真实模型 " + llmClient.model() + "）===");
        System.out.print(ChainExperiment.table(rows));
        System.out.println(summary.toReport());

        assertThat(rows).as("实验必须有样本").isNotEmpty();
        rows.forEach(row -> assertThat(row.multiHop().stopReason())
                .as("每一题都必须有明确的终止原因（预算耗尽也算终止，失控不算）—— %s",
                        row.question().target().qualifiedName())
                .isNotNull());
        assertThat(rows.stream().anyMatch(row -> row.multiHop().found() > 0))
                .as("真实模型至少要在一题里查到链路上的上游（否则说明工具或提示词根本不可用）")
                .isTrue();
        assertThat(summary.multiHopRecall())
                .as("多跳的完整度不该低于单跳上限（否则就是白花钱）")
                .isGreaterThanOrEqualTo(summary.singleHopBestRecall());
    }

    /**
     * 挑**小链路**的题：真值比直接调用者多、但规模可控。
     *
     * <p>真实模型跑一题要好几秒和几千 token —— 链路一大，这个实验就从"验证"变成"烧钱"，
     * 而结论不会因为链更长而改变方向。
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
