package com.readcodeai.retrieve;

import com.readcodeai.config.EmbeddingClient;
import com.readcodeai.config.ReadCodeAiProperties;
import com.readcodeai.index.store.ChunkEmbeddingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量补算：把一个仓库 SYMBOL 类 chunk 的向量**按需、批量、缓存式**地补齐。
 *
 * <h3>为什么不在索引流程里顺手做</h3>
 * 索引有一条硬承诺：**没配任何模型也能完整跑通**（可降级设计的地基）。
 * 把 embedding 塞进索引流程，索引就从"确定性的本地计算"变成了"依赖外部接口的流程"，
 * 这条承诺就破了。所以向量是**旁路**：要用向量检索的人（现在只有对比实验，将来是模糊查找）
 * 先调 {@link #ensureEmbedded}，缺多少补多少，补完落库缓存。
 *
 * <h3>为什么是"按 hash 判缺"而不是"有没有"</h3>
 * 重新索引后 chunk 内容会变（id 也会变），旧向量对不上新内容就是错的。
 * 用 content_hash 比对，内容没变的 chunk 永远不用重算 —— 重跑一次实验的成本因此是零。
 */
@Service
public class VectorIndexService {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexService.class);

    /** 单条文本的截断长度：超长方法整个塞进去没有意义，还会顶到接口的输入上限。 */
    private static final int MAX_CONTENT_CHARS = 6000;

    private final EmbeddingClient embedding;
    private final ChunkEmbeddingRepository repository;
    private final ReadCodeAiProperties properties;

    public VectorIndexService(EmbeddingClient embedding, ChunkEmbeddingRepository repository,
                              ReadCodeAiProperties properties) {
        this.embedding = embedding;
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * @param embedded 这次真正新算的条数（缓存命中的不计）
     * @param tokens   这次新算花掉的 token（缓存命中时为 0 —— 成本要能报出"省了多少"）
     */
    public record Summary(int embedded, int tokens, int totalSymbolChunks, int alreadyCached) {
    }

    public Summary ensureEmbedded(long repoId) {
        if (!embedding.available()) {
            throw new IllegalStateException("向量补算不可用（" + embedding.describe() + "）");
        }
        int batchSize = properties.getEmbedding().getBatchSize();
        int total = repository.countSymbolChunks(repoId);
        int embedded = 0;
        int tokens = 0;

        while (true) {
            List<ChunkEmbeddingRepository.MissingChunk> missing =
                    repository.selectMissingChunks(repoId, embedding.model(), embedding.dimensions(), batchSize);
            if (missing.isEmpty()) {
                break;
            }
            List<String> inputs = new ArrayList<>(missing.size());
            for (ChunkEmbeddingRepository.MissingChunk chunk : missing) {
                String content = chunk.content();
                if (content.length() > MAX_CONTENT_CHARS) {
                    content = content.substring(0, MAX_CONTENT_CHARS);
                }
                inputs.add(content);
            }
            EmbeddingClient.EmbeddingBatch batch = embedding.embed(inputs);
            repository.upsertBatch(repoId, embedding.model(), embedding.dimensions(), missing, batch.vectors());
            embedded += missing.size();
            tokens += batch.promptTokens();
            log.info("向量补算：{} / {} 个 chunk（本批 {} 条，累计 {} token）",
                    embedded, total, missing.size(), tokens);
        }

        return new Summary(embedded, tokens, total, total - embedded);
    }
}
