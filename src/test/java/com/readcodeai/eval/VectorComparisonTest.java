package com.readcodeai.eval;

import com.readcodeai.agent.AnswerService;
import com.readcodeai.agent.ScriptedLlmClient;
import com.readcodeai.config.ReadCodeAiProperties;
import com.readcodeai.evidence.EvidenceRepair;
import com.readcodeai.evidence.EvidenceVerifier;
import com.readcodeai.eval.model.GeneratedQuestion;
import com.readcodeai.index.ProjectIndexer;
import com.readcodeai.index.store.ChunkEmbeddingRepository;
import com.readcodeai.index.store.TextSearchRepository;
import com.readcodeai.retrieve.ContextSelector;
import com.readcodeai.retrieve.QueryRouter;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.TextRetriever;
import com.readcodeai.retrieve.VectorIndexService;
import com.readcodeai.retrieve.VectorRetriever;
import com.readcodeai.retrieve.model.RepoView;
import com.readcodeai.verify.HashEmbeddingClient;
import com.readcodeai.verify.TestCheckers;
import com.readcodeai.verify.TestCorpus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 第一组对比实验的**离线**验证（假向量，零网络）。
 *
 * <p>它不产出效果数字 —— 那是真实 embedding 实验的活。它钉住的是实验本身的量尺：
 * <ul>
 *   <li><b>确定性路由那一方的召回必须恒等于 1.0</b>（它走的就是真值那条路）——
 *       这条断了，说明路由或判卷出了问题，A 方的数字全部作废；</li>
 *   <li>用"一被调用就抛错"的脚本模型当 LLM：五类题必须全部走确定性路线，
 *       有一题溜进了模型路线就当场暴露。</li>
 * </ul>
 */
@SpringBootTest
class VectorComparisonTest {

    private static final long SEED = 20260918L;

    private static final int PER_TYPE = 3;

    private static final ScriptedLlmClient.Script MUST_NOT_BE_CALLED = (turn, prompt) -> {
        throw new AssertionError("评估题应当全部走确定性路线，模型却在第 " + turn + " 轮被调用了");
    };

    @Autowired
    private TextRetriever textRetriever;

    @Autowired
    private SymbolQueryService queries;

    @Autowired
    private QueryRouter queryRouter;

    @Autowired
    private ContextSelector contextSelector;

    @Autowired
    private EvidenceVerifier evidenceVerifier;

    @Autowired
    private EvidenceRepair evidenceRepair;

    @Autowired
    private ChunkEmbeddingRepository embeddings;

    @Autowired
    private TextSearchRepository chunks;

    @Autowired
    private QuestionGenerator generator;

    @Autowired
    private ProjectIndexer indexer;

    @Autowired
    private ReadCodeAiProperties properties;

    @Test
    void routeSideMustBePerfectAndTheExperimentMustStayDeterministic() {
        RepoView repo = corpus();
        long repoId = repo.id();

        HashEmbeddingClient embedding = new HashEmbeddingClient(64);
        new VectorIndexService(embedding, embeddings, properties).ensureEmbedded(repoId);
        VectorRetriever vectorRetriever = new VectorRetriever(embedding, embeddings, chunks);

        // 确定性路由专用：五类评估题都不该碰模型
        AnswerService routesOnly = new AnswerService(textRetriever, queries, queryRouter, contextSelector,
                evidenceVerifier, evidenceRepair, TestCheckers.NONE,
                new ScriptedLlmClient(MUST_NOT_BE_CALLED), properties);

        List<GeneratedQuestion> questions = generator.generate(repoId, SEED, PER_TYPE);
        assumeTrue(questions.size() >= 8, "语料里可出的题太少，跳过");

        VectorComparison.Result result = new VectorComparison(routesOnly, vectorRetriever)
                .run(repoId, questions);
        System.out.println(System.lineSeparator() + "=== 第一组对比实验（离线 · 假向量，只验证量尺）===");
        System.out.print(VectorComparison.table(result));

        for (VectorComparison.Row row : result.rows()) {
            assertThat(row.routeRecall())
                    .as("确定性路由的召回按构造应为 1.0（量尺自检），出错的题：%s", row.question())
                    .isEqualTo(1.0);
        }
        assertThat(result.summary().perType())
                .as("五种题型都应当有题（否则这次的语料撑不起对比）").hasSize(5);
    }

    private RepoView corpus() {
        var repo = TestCorpus.resolve(indexer, queries);
        assumeTrue(repo.isPresent(), "语料不存在（sample-repos 或 -Dreadcodeai.verify.repo=），跳过");
        return repo.get();
    }
}
