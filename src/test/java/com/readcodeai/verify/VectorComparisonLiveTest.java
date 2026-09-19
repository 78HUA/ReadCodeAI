package com.readcodeai.verify;

import com.readcodeai.agent.AnswerService;
import com.readcodeai.agent.ModelJson;
import com.readcodeai.config.EmbeddingClient;
import com.readcodeai.config.LlmClient;
import com.readcodeai.eval.QuestionGenerator;
import com.readcodeai.eval.VectorComparison;
import com.readcodeai.eval.model.GeneratedQuestion;
import com.readcodeai.eval.model.QType;
import com.readcodeai.index.ProjectIndexer;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.TextRetriever;
import com.readcodeai.retrieve.VectorIndexService;
import com.readcodeai.retrieve.VectorRetriever;
import com.readcodeai.retrieve.model.ChunkHit;
import com.readcodeai.retrieve.model.RepoView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 第一组对比实验（**真实 embedding**）：纯向量检索 vs 现有三层检索。
 *
 * <p>没配 Key 或 embedding 接口不可达时自动跳过 —— 它产出的是**实测数字**
 * （分题型召回、成本、耗时），机制本身已由离线测试覆盖。
 *
 * <p>两个数字必须分开说（与第三组实验同一纪律）：
 * <ul>
 *   <li>「确定性路由召回 = 100%」是**量尺自检**，不是效果数字；</li>
 *   <li>「纯向量召回 X%」才是这次的产出 —— 它就是"为什么不把检索全交给向量"的实证。</li>
 * </ul>
 *
 * <p>运行前 {@code source notes/llm-env.sh}。embedding-3 是**付费档**（对话的 glm-4-flash 免费档
 * 与它分开计费），首次会为整个语料补算向量；之后按 (chunk, model, content_hash) 缓存，重跑零成本。
 */
@SpringBootTest
class VectorComparisonLiveTest {

    private static final long SEED = 20260918L;

    private static final int PER_TYPE = 30;

    /** 中文改写子实验的题数：改写是一题一次模型调用，取样点到为止。 */
    private static final int CHINESE_QUESTIONS = 12;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private VectorIndexService vectorIndex;

    @Autowired
    private VectorRetriever vectorRetriever;

    @Autowired
    private AnswerService answerService;

    @Autowired
    private TextRetriever textRetriever;

    @Autowired
    private LlmClient llmClient;

    @Autowired
    private QuestionGenerator generator;

    @Autowired
    private ProjectIndexer indexer;

    @Autowired
    private SymbolQueryService queries;

    @Test
    void runsTheFirstComparisonAgainstTheRealEmbeddingModel() {
        LiveLlm.assumeReachable(llmClient);
        assumeTrue(embeddingClient.available(), "向量化客户端未启用，跳过");
        // embedding 是另一个端点、另一种计费，连通性要单独探测（比对话多一道闸）
        try {
            embeddingClient.embed(List.of("连通性探测"));
        } catch (RuntimeException e) {
            assumeTrue(false, "embedding 接口当前不可用（" + e.getClass().getSimpleName() + "："
                    + firstLine(e.getMessage()) + "），跳过 —— 属外部波动/权限问题，与代码无关");
        }

        RepoView repo = corpus();
        VectorIndexService.Summary embedded = vectorIndex.ensureEmbedded(repo.id());
        System.out.printf("[向量补算] 本次新算 %d 条 · 已缓存 %d 条 · 总数 %d · 本次 %d token%n",
                embedded.embedded(), embedded.alreadyCached(), embedded.totalSymbolChunks(),
                embedded.tokens());
        // 重跑零成本是缓存设计的前提，值得断言
        VectorIndexService.Summary again = vectorIndex.ensureEmbedded(repo.id());
        assertThat(again.embedded()).as("同一仓库立刻重算应全部命中缓存").isZero();

        List<GeneratedQuestion> questions = generator.generate(repo.id(), SEED, PER_TYPE);
        assumeTrue(questions.size() >= 20, "语料里可出的题太少，跳过");
        VectorComparison.Result result = new VectorComparison(answerService, vectorRetriever)
                .run(repo.id(), questions);
        System.out.println(System.lineSeparator()
                + "=== 第一组对比实验（真实 embedding：" + embeddingClient.describe() + "）===");
        System.out.print(VectorComparison.table(result));

        for (VectorComparison.Row row : result.rows()) {
            assertThat(row.routeRecall())
                    .as("量尺自检：确定性路由召回恒为 1.0，出错的题：%s", row.question())
                    .isEqualTo(1.0);
        }
    }

    /**
     * 中文子实验：把定位题改写成**不含任何英文标识符的纯中文**，对比全文检索与向量检索的召回。
     *
     * <p>这就是立项时那个"中文提问 ↔ 英文标识符"鸿沟的量化。两点诚实声明：
     * <ul>
     *   <li>改写用的是对话模型，**改写质量不可控** —— 题面可能比真实用户的中文问法更含糊或更清晰，
     *       数字标"参考"，不进验收；</li>
     *   <li>真值与判卷仍然不经过模型（真值 = 静态分析，判卷 = 行区间覆盖），改写只改变题面。</li>
     * </ul>
     */
    @Test
    void chineseQuestionsShowTheGapBetweenFulltextAndVector() {
        LiveLlm.assumeReachable(llmClient);
        assumeTrue(embeddingClient.available(), "向量化客户端未启用，跳过");
        RepoView repo = corpus();
        vectorIndex.ensureEmbedded(repo.id());

        List<GeneratedQuestion> locateQuestions = generator.generate(repo.id(), SEED, PER_TYPE).stream()
                .filter(question -> question.type() == QType.LOCATE)
                .limit(CHINESE_QUESTIONS)
                .toList();
        assumeTrue(!locateQuestions.isEmpty(), "语料里没有定位题，跳过");

        System.out.println(System.lineSeparator() + "=== 中文子实验（纯中文题面 · 真值仍是静态分析）===");
        System.out.println("  题面里已无英文标识符 | 全文召回 | 向量召回 | 改写后的题面");
        int usable = 0;
        double fulltextRecallSum = 0;
        double vectorRecallSum = 0;
        int rewritesStillWithIdentifier = 0;
        for (GeneratedQuestion question : locateQuestions) {
            String rewritten;
            try {
                rewritten = rewriteInChinese(question.questionText());
            } catch (RuntimeException e) {
                System.out.printf("  改写失败（%s），跳过该题%n", e.getClass().getSimpleName());
                continue;
            }
            boolean clean = !rewritten.matches(".*[A-Za-z]{3,}.*");
            if (!clean) {
                // 改写不干净的题继续算会污染结论：单独计数，不进平均
                rewritesStillWithIdentifier++;
                System.out.printf("  %-20s | — | — | （改写仍含标识符，弃用）%s%n", "改写不干净", rewritten);
                continue;
            }
            usable++;
            List<ChunkHit> fulltext = textRetriever.search(repo.id(), rewritten, VectorComparison.TOP_K);
            List<ChunkHit> vector = vectorRetriever.search(repo.id(), rewritten, VectorComparison.TOP_K);
            Set<String> truth = new LinkedHashSet<>(question.truthKeys());
            double fulltextRecall = VectorComparison.recallBySpans(fulltext, truth);
            double vectorRecall = VectorComparison.recallBySpans(vector, truth);
            fulltextRecallSum += fulltextRecall;
            vectorRecallSum += vectorRecall;
            System.out.printf("  %-20s | %7.0f%% | %7.0f%% | %s%n",
                    "是", fulltextRecall * 100, vectorRecall * 100, abbreviate(rewritten));
        }
        if (usable > 0) {
            System.out.printf("%n  可用改写题 %d 条（另有 %d 条改写不干净被弃用）：全文平均召回 %.0f%% · 向量平均召回 %.0f%%"
                            + "（仅供参考：改写质量不可控）%n",
                    usable, rewritesStillWithIdentifier,
                    fulltextRecallSum / usable * 100, vectorRecallSum / usable * 100);
        } else {
            System.out.printf("%n  没有可用的改写题（%d 条全部仍含标识符）—— 这本身就是个小发现："
                    + "小模型做不好\"纯中文改写\"，这个子实验的路子要重新设计%n", rewritesStillWithIdentifier);
        }
    }

    private String rewriteInChinese(String question) {
        String system = """
                你要把一道代码检索题改写成**纯中文**：不能出现任何英文标识符、类名、方法名、
                文件路径或包名，也不得改变它在问什么。只输出 JSON：
                {"question":"改写后的题目"}""";
        LlmClient.Completion completion = llmClient.complete(system, "题目：" + question);
        Rewritten rewritten = ModelJson.parse(completion.content(), Rewritten.class);
        if (rewritten == null || rewritten.question() == null || rewritten.question().isBlank()) {
            throw new IllegalStateException("改写结果为空");
        }
        return rewritten.question().strip();
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record Rewritten(String question) {
    }

    private RepoView corpus() {
        var repo = TestCorpus.resolve(indexer, queries);
        assumeTrue(repo.isPresent(), "语料不存在（sample-repos 或 -Dreadcodeai.verify.repo=），跳过");
        return repo.get();
    }

    private static String abbreviate(String text) {
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= 60 ? oneLine : oneLine.substring(0, 60) + "...";
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "(无消息)";
        }
        int newline = message.indexOf('\n');
        String line = newline < 0 ? message : message.substring(0, newline);
        return line.length() <= 160 ? line : line.substring(0, 160) + "...";
    }
}
