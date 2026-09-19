package com.readcodeai.retrieve;

import com.readcodeai.config.ReadCodeAiProperties;
import com.readcodeai.index.ProjectIndexer;
import com.readcodeai.index.store.ChunkEmbeddingRepository;
import com.readcodeai.index.store.TextSearchRepository;
import com.readcodeai.retrieve.model.ChunkHit;
import com.readcodeai.retrieve.model.RepoView;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.verify.HashEmbeddingClient;
import com.readcodeai.verify.TestCorpus;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 向量检索的机制验收（**假向量客户端**，零网络、完全确定）。
 *
 * <p>要钉住的是四件事：
 * <ol>
 *   <li>**检索数学是对的**：文本相同的 chunk 必须以 1.0 的分数排第一</li>
 *   <li>**补算是"按需 + 缓存"的**：第二次 ensureEmbedded 一条都不重算</li>
 *   <li>**缺向量是配置问题不是空结果**：必须大声报错，不能静默返回空列表</li>
 *   <li>**top-k 与降序**：返回条数不超、分数单调不增</li>
 * </ol>
 * 真实 embedding 模型下的效果数字归 {@code VectorComparisonLiveTest}，这里不碰网络。
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VectorRetrieverTest {

    @Autowired
    private ChunkEmbeddingRepository embeddings;

    @Autowired
    private TextSearchRepository chunks;

    @Autowired
    private ProjectIndexer indexer;

    @Autowired
    private SymbolQueryService queries;

    @Autowired
    private ReadCodeAiProperties properties;

    private final HashEmbeddingClient embedding = new HashEmbeddingClient(64);

    private VectorRetriever retriever() {
        return new VectorRetriever(embedding, embeddings, chunks);
    }

    private VectorIndexService indexerService() {
        return new VectorIndexService(embedding, embeddings, properties);
    }

    private RepoView corpus() {
        var repo = TestCorpus.resolve(indexer, queries);
        assumeTrue(repo.isPresent(), "语料不存在（sample-repos 或 -Dreadcodeai.verify.repo=），跳过");
        return repo.get();
    }

    @Test
    @Order(1)
    void searchingBeforeEmbeddingFailsLoudlyInsteadOfReturningEmpty() {
        long repoId = corpus().id();
        // 刻意用**别的维度**（48 而不是 64）构造检索器：不管别的测试类有没有先补算过 64 维，
        // 48 维这个组合永远没有向量 —— 断言于是与测试执行顺序无关
        VectorRetriever freshDimension = new VectorRetriever(
                new com.readcodeai.verify.HashEmbeddingClient(48), embeddings, chunks);
        assertThatThrownBy(() -> freshDimension.search(repoId, "TypeAdapter read", 8))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("还没有向量");
    }

    @Test
    @Order(2)
    void identicalTextIsRetrievedFirstWithPerfectScore() {
        long repoId = corpus().id();
        VectorIndexService.Summary summary = indexerService().ensureEmbedded(repoId);
        System.out.printf("[向量补算] 新算 %d 条 · 已缓存 %d 条 · 总数 %d · %d token%n",
                summary.embedded(), summary.alreadyCached(), summary.totalSymbolChunks(), summary.tokens());
        assumeTrue(summary.totalSymbolChunks() > 0, "语料里没有 SYMBOL chunk，跳过");

        // 拿语料里某个 chunk 的**原文**当查询：词袋向量完全相同 → 余弦必为 1.0、必排第一。
        // 刻意挑短内容：补算时超过 6000 字的 chunk 是**截断后**嵌入的，拿全文当查询就不再是同一向量，
        // "自身得 1.0"的前提不成立（第一版没挑，实测就栽在这——top-1 落到了别的文件上）
        ChunkHit someChunk = chunks.selectSymbolChunks(repoId).stream()
                .filter(chunk -> chunk.content().length() <= 2000)
                .findFirst()
                .orElseThrow();
        assumeTrue(someChunk.content().length() > 0, "语料里的 chunk 内容为空，跳过");
        List<ChunkHit> hits = retriever().search(repoId, someChunk.content(), 8);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).score()).as("最高分应是 1.0（查询本身就是某 chunk 的原文）")
                .isGreaterThanOrEqualTo(0.999);
        assertThat(hits.stream().filter(hit -> hit.filePath().equals(someChunk.filePath())
                && hit.startLine() == someChunk.startLine()).findFirst())
                .as("原文那个 chunk 必须在命中里拿到满分（若语料里有内容完全相同的 chunk，允许并列第一）")
                .isPresent()
                .hasValueSatisfying(hit -> assertThat(hit.score()).isGreaterThanOrEqualTo(0.999));
        assertThat(hits).as("分数必须降序").isSortedAccordingTo(
                (a, b) -> Double.compare(b.score(), a.score()));
        assertThat(hits.size()).isLessThanOrEqualTo(8);
    }

    @Test
    @Order(3)
    void secondEmbeddingRunCostsNothing() {
        long repoId = corpus().id();
        VectorIndexService.Summary first = indexerService().ensureEmbedded(repoId);
        VectorIndexService.Summary second = indexerService().ensureEmbedded(repoId);

        assumeTrue(first.totalSymbolChunks() > 0, "语料里没有 SYMBOL chunk，跳过");
        assertThat(second.embedded()).as("内容没变就一条都不重算（重跑实验零成本的前提）").isZero();
        assertThat(second.tokens()).isZero();
        assertThat(second.alreadyCached()).isEqualTo(second.totalSymbolChunks());
    }

    @Test
    void unavailableClientFailsWithAClearMessage() {
        com.readcodeai.config.NoopEmbeddingClient noop =
                new com.readcodeai.config.NoopEmbeddingClient("测试：显式关闭");
        VectorRetriever unavailable = new VectorRetriever(noop, embeddings, chunks);

        assertThatThrownBy(() -> unavailable.search(1, "anything", 8))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("向量检索不可用");
    }
}
