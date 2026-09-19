package com.readcodeai.retrieve;

import com.readcodeai.config.EmbeddingClient;
import com.readcodeai.index.store.ChunkEmbeddingRepository;
import com.readcodeai.index.store.TextSearchRepository;
import com.readcodeai.retrieve.model.ChunkHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 第 3 层检索：**向量相似度**（概率性手段），目前只作为**对比实验的基线**存在。
 *
 * <h3>它为什么没有接进问答路由</h3>
 * 「谁调用了 X」「X 定义在哪」这类确定性问题的答案是符号表/调用图**算出来的**，
 * 把它们交给向量相似度是把确定换成概率 —— 净亏（这正是第一组对比实验要用数字证明的事）。
 * 它将来若接路由，服务的只是"模糊语义查找"那一类问题，判据见 design-outline.md。
 *
 * <h3>规模判据（写清楚，不装看不见）</h3>
 * 全量向量加载进内存 + 线性扫余弦，万级 chunk（约几 MB）毫无压力；
 * 到了十万级 chunk 或多实例共享时，该换近似索引 / 向量库 —— 那是判据触发后的另一笔账。
 */
@Component
public class VectorRetriever {

    private static final Logger log = LoggerFactory.getLogger(VectorRetriever.class);

    private final EmbeddingClient embedding;
    private final ChunkEmbeddingRepository embeddings;
    private final TextSearchRepository chunks;

    /** 进程内缓存：键 = repoId:model:维度。重新索引会产生**新的 repoId**，所以不存在过期问题。 */
    private final Map<String, Loaded> cache = new ConcurrentHashMap<>();

    private record Loaded(Map<Long, float[]> vectors, Map<Long, ChunkHit> metadata) {
    }

    public VectorRetriever(EmbeddingClient embedding, ChunkEmbeddingRepository embeddings,
                           TextSearchRepository chunks) {
        this.embedding = embedding;
        this.embeddings = embeddings;
        this.chunks = chunks;
    }

    public String describe() {
        return embedding.describe();
    }

    /**
     * 向量 top-k 检索。分数是余弦相似度，**降序**返回。
     *
     * @throws IllegalStateException 未配置向量化、或该仓库还没补算过向量（两者都是配置问题，不是"没有结果"）
     */
    public List<ChunkHit> search(long repoId, String query, int topK) {
        if (!embedding.available()) {
            throw new IllegalStateException("向量检索不可用（" + embedding.describe() + "）："
                    + "符号表 / 调用图 / 全文检索等确定性能力不受影响");
        }
        Loaded loaded = load(repoId);
        if (loaded.vectors().isEmpty()) {
            throw new IllegalStateException("向量检索不可用：仓库 " + repoId
                    + " 还没有向量（先执行 VectorIndexService.ensureEmbedded）");
        }
        float[] queryVector = embedding.embed(List.of(query)).vectors().get(0);

        List<Scored> scored = new ArrayList<>(loaded.vectors().size());
        for (Map.Entry<Long, float[]> entry : loaded.vectors().entrySet()) {
            scored.add(new Scored(entry.getKey(), cosine(queryVector, entry.getValue())));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());

        List<ChunkHit> hits = new ArrayList<>(Math.min(topK, scored.size()));
        for (int i = 0; i < topK && i < scored.size(); i++) {
            Scored item = scored.get(i);
            ChunkHit metadata = loaded.metadata().get(item.chunkId());
            if (metadata == null) {
                // 有向量没有元数据 = 索引和向量表不一致（理论上外键级联不会出现），跳过而不是返回残缺结果
                log.warn("chunk {} 有向量但没有元数据，跳过", item.chunkId());
                continue;
            }
            hits.add(new ChunkHit(metadata.chunkId(), metadata.kind(), metadata.filePath(),
                    metadata.startLine(), metadata.endLine(), metadata.symbolId(),
                    metadata.symbolQualifiedName(), metadata.tokenEstimate(), item.score(), metadata.content()));
        }
        return hits;
    }

    /**
     * 加载向量与元数据；**空结果不缓存** —— 否则"先检索（空）、再补算"的顺序
     * 会把空缓存钉死在进程里，补算完了也还是查不到。
     */
    private Loaded load(long repoId) {
        String key = repoId + ":" + embedding.model() + ":" + embedding.dimensions();
        Loaded cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        Map<Long, float[]> vectors = new HashMap<>();
        for (ChunkEmbeddingRepository.StoredVector stored : embeddings.loadAll(repoId, embedding.model(),
                embedding.dimensions())) {
            vectors.put(stored.chunkId(), stored.vector());
        }
        Loaded loaded = new Loaded(vectors, new HashMap<>());
        if (!vectors.isEmpty()) {
            for (ChunkHit chunk : chunks.selectSymbolChunks(repoId)) {
                loaded.metadata().put(chunk.chunkId(), chunk);
            }
            cache.put(key, loaded);
        }
        return loaded;
    }

    /** 余弦相似度。不假设向量已归一化（实测归一化是供应商的实现细节，不该赌）。 */
    static double cosine(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        int length = Math.min(a.length, b.length);
        for (int i = 0; i < length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / Math.sqrt(normA * normB);
    }

    private record Scored(long chunkId, double score) {
    }
}
