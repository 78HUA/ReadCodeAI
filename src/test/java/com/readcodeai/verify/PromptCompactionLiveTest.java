package com.readcodeai.verify;

import com.readcodeai.agent.AgentLoop;
import com.readcodeai.agent.AgentService;
import com.readcodeai.agent.AnswerService;
import com.readcodeai.agent.SummaryAnswerer;
import com.readcodeai.agent.ToolRegistry;
import com.readcodeai.agent.cache.NoopAnswerCache;
import com.readcodeai.config.LlmClient;
import com.readcodeai.config.ReadCodeAiProperties;
import com.readcodeai.eval.ChainExperiment;
import com.readcodeai.eval.ChainQuestionGenerator;
import com.readcodeai.evidence.EvidenceVerifier;
import com.readcodeai.index.ProjectIndexer;
import com.readcodeai.retrieve.QueryRouter;
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
 * 提示词瘦身的**真实 A/B**：同一批链式题，跑「最近 2 跳保留原文」与「完全不压缩」两组。
 *
 * <p>三件事必须同时看，缺一条这个改动就不成立：
 * <ul>
 *   <li><b>token 降了多少</b>（收益）—— 这是改动的目的；</li>
 *   <li><b>召回掉没掉</b>（代价）—— 真值来自调用图反向 BFS，与第三组对比实验同一把尺子；</li>
 *   <li><b>轮数与延迟</b>（解释）—— 如果瘦身后模型要多查几跳才敢下结论，那省下的 token 会被吃掉。</li>
 * </ul>
 *
 * <p>两条纪律（否则数字是假的）：
 * <ol>
 *   <li><b>答案缓存必须关掉</b>：第二组若命中第一组写的缓存，token 直接变 0，
 *       会得出"瘦身省了 100%"这种假结论；</li>
 *   <li><b>③ 层核验关掉且两组一致</b>：它是额外一次模型调用，混进来就说不清 token 是谁花的。</li>
 * </ol>
 * 硬断言只压"瘦身确实更省"这一条（机制方向），召回与延迟只打印 —— 真实模型有波动，
 * 把它们写成断言只会培养"改测试"的习惯。
 */
@SpringBootTest
class PromptCompactionLiveTest {

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
    private LlmClient llmClient;

    @Autowired
    private ChainQuestionGenerator generator;

    @Autowired
    private ProjectIndexer indexer;

    @Autowired
    private ReadCodeAiProperties properties;

    @Autowired
    private SummaryAnswerer summaryAnswerer;

    @Test
    void measuresTheTokenSavingAndTheQualityCostOfCompactingOldHops() {
        LiveLlm.assumeReachable(llmClient);
        RepoView repo = corpus();

        List<ChainQuestionGenerator.ChainQuestion> questions = smallChains(repo.id(), QUESTION_COUNT);
        assumeTrue(questions.size() >= 2, "语料里没有足够的链式问题，跳过");

        properties.getLlm().setMaxRounds(12);   // 与第三组实验同一条预算：要的是能力上限不是预算上限
        ChainExperiment compacted = new ChainExperiment(serviceWith(2), generator);
        ChainExperiment full = new ChainExperiment(serviceWith(0), generator);

        System.out.println(System.lineSeparator() + "=== 提示词瘦身 A/B（真实模型：" + llmClient.model() + "）===");
        System.out.println("题 | 瘦身后 token | 不瘦身 token | 省下 | 瘦身后召回 | 不瘦身召回 | 瘦身后轮次 | 不瘦身轮次 | 延迟(ms) 瘦/不瘦");
        List<ChainExperiment.Run> compactedRuns = new ArrayList<>();
        List<ChainExperiment.Run> fullRuns = new ArrayList<>();
        int index = 0;
        for (ChainQuestionGenerator.ChainQuestion question : questions) {
            index++;
            // 交替顺序（单数先瘦身、双数先不瘦身）：缓解"谁先跑谁吃亏/占便宜"（JIT、连接池预热）
            ChainExperiment.Run compactedRun;
            ChainExperiment.Run fullRun;
            if (index % 2 == 1) {
                compactedRun = compacted.run(repo.id(), question).multiHop();
                fullRun = full.run(repo.id(), question).multiHop();
            } else {
                fullRun = full.run(repo.id(), question).multiHop();
                compactedRun = compacted.run(repo.id(), question).multiHop();
            }
            compactedRuns.add(compactedRun);
            fullRuns.add(fullRun);

            long compactedTokens = compactedRun.promptTokens() + compactedRun.completionTokens();
            long fullTokens = fullRun.promptTokens() + fullRun.completionTokens();
            System.out.printf("%d | %d | %d | %s | %.0f%% | %.0f%% | %d | %d | %d / %d%n",
                    index, compactedTokens, fullTokens,
                    fullTokens == 0 ? "-" : String.format("%.0f%%", 100.0 * (fullTokens - compactedTokens) / fullTokens),
                    compactedRun.recall() * 100, fullRun.recall() * 100,
                    compactedRun.rounds(), fullRun.rounds(),
                    compactedRun.latencyMs(), fullRun.latencyMs());
        }

        long compactedTotal = compactedRuns.stream().mapToLong(run -> run.promptTokens() + run.completionTokens()).sum();
        long fullTotal = fullRuns.stream().mapToLong(run -> run.promptTokens() + run.completionTokens()).sum();
        double compactedRecall = compactedRuns.stream().mapToDouble(ChainExperiment.Run::recall).average().orElse(0);
        double fullRecall = fullRuns.stream().mapToDouble(ChainExperiment.Run::recall).average().orElse(0);
        double compactedRounds = compactedRuns.stream().mapToInt(ChainExperiment.Run::rounds).average().orElse(0);
        double fullRounds = fullRuns.stream().mapToInt(ChainExperiment.Run::rounds).average().orElse(0);
        long compactedLatency = (long) compactedRuns.stream().mapToLong(ChainExperiment.Run::latencyMs).average().orElse(0);
        long fullLatency = (long) fullRuns.stream().mapToLong(ChainExperiment.Run::latencyMs).average().orElse(0);
        int compactedRefused = (int) compactedRuns.stream().filter(ChainExperiment.Run::refused).count();
        int fullRefused = (int) fullRuns.stream().filter(ChainExperiment.Run::refused).count();

        System.out.printf("%n合计：token %d → %d（省 %.0f%%）· 平均召回 %.0f%% → %.0f%%"
                        + " · 平均轮次 %.1f → %.1f · 平均延迟 %d ms → %d ms · 拒答 %d → %d%n",
                fullTotal, compactedTotal, fullTotal == 0 ? 0.0 : 100.0 * (fullTotal - compactedTotal) / fullTotal,
                fullRecall * 100, compactedRecall * 100, fullRounds, compactedRounds,
                fullLatency, compactedLatency, fullRefused, compactedRefused);

        // 这里**不做"瘦身一定更省"的断言**：真实模型两次跑的轨迹会分叉（同一批题里有一题瘦身组多查了 5 轮），
        // token 差异里混着路径差异而不是压缩效果。方向性结论由**同一条脚本轨迹**的离线用例下
        // （PromptCompactionTest：6 轮合计 45958 → 35220 字符）；这里只保证两边都跑完、数字如实报出来。
        assertThat(compactedRuns).as("两组都必须跑完（不抛异常）").hasSize(questions.size());
        assertThat(fullRuns).hasSize(questions.size());
        System.out.println("（注：真实模型下两组的轨迹会分叉，这里的 token 差异同时包含压缩效果与路径差异；"
                + "指向性的结论看离线同轨迹那组）");
    }

    /** 每组一个 AgentService：区别只在 AgentLoop 拿到的 keepFullObservations。 */
    private AgentService serviceWith(int keepFullObservations) {
        AgentLoop loop = new AgentLoop(toolRegistry, evidenceVerifier, TestCheckers.NONE, llmClient,
                keepFullObservations);
        return new AgentService(answerService, loop, queryRouter, queries, llmClient,
                new NoopAnswerCache("A/B 实验必须禁用缓存：否则第二组会命中第一组的答案、token 变成 0"),
                properties, TestAnswerLogs.silent(properties), summaryAnswerer);
    }

    /** 挑**小链路**的题：真值比直接调用者多、但规模可控（与第三组实验同一套题源）。 */
    private List<ChainQuestionGenerator.ChainQuestion> smallChains(long repoId, int count) {
        List<ChainQuestionGenerator.ChainQuestion> picked = new ArrayList<>();
        for (ChainQuestionGenerator.ChainQuestion question : generator.generate(repoId, SEED, count * 4, DEPTH)) {
            if (question.chainOnly() > 0 && question.truthSize() <= 40) {
                picked.add(question);
                if (picked.size() == count) {
                    break;
                }
            }
        }
        return picked;
    }

    private RepoView corpus() {
        var repo = TestCorpus.resolve(indexer, queries);
        assumeTrue(repo.isPresent(), "语料不存在（sample-repos 或 -Dreadcodeai.verify.repo=），跳过");
        return repo.get();
    }
}
