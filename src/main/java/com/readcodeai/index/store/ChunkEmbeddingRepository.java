package com.readcodeai.index.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * `chunk_embedding` 表的数据访问：**向量按 (chunk, model) 隔离，用 content_hash 判新旧**。
 *
 * <p>两条不变量：
 * <ul>
 *   <li>换模型 / 换维度 = 换向量空间，旧向量不可比 —— 所以查询都带 model（维度不符按缺失处理，
 *       下次补算会整行覆盖）；</li>
 *   <li>chunk 内容变了，旧向量就是错的 —— 所以"缺不缺"不看有没有行，看 hash 是否还一致。</li>
 * </ul>
 *
 * <p>为什么向量存 BLOB 而不引向量库：万级 chunk × 1024 维 ≈ 几 MB，进程内算余弦绰绰有余。
 * 判据（几十万 chunk / 多实例共享）写在 design-outline.md，到了再换，不提前付复杂度。
 */
@Repository
public class ChunkEmbeddingRepository {

    /** 待补算的 chunk：内容与内容 hash 一起取，嵌入后 hash 一起落库。 */
    public record MissingChunk(long chunkId, String content, String contentHash) {
    }

    public record StoredVector(long chunkId, float[] vector) {
    }

    private final JdbcTemplate jdbc;

    public ChunkEmbeddingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 仓库里 SYMBOL 类 chunk 的总数（补算进度的分母）。 */
    public int countSymbolChunks(long repoId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `chunk` WHERE repo_id = ? AND kind = 'SYMBOL'",
                Integer.class, repoId);
        return count == null ? 0 : count;
    }

    /**
     * 找出还没有**有效**向量的 chunk：没有行、或行里的 hash/维度与当前不一致。
     * 空内容的 chunk 永远跳过 —— 空文本没有语义，还会让补算循环永远停不下来。
     */
    public List<MissingChunk> selectMissingChunks(long repoId, String model, int dimensions, int limit) {
        return jdbc.query("""
                SELECT c.id, c.content_hash, c.content
                  FROM `chunk` c
                  LEFT JOIN `chunk_embedding` e ON e.chunk_id = c.id AND e.model = ?
                 WHERE c.repo_id = ? AND c.kind = 'SYMBOL' AND TRIM(c.content) <> ''
                   AND (e.id IS NULL OR e.content_hash <> c.content_hash OR e.dimensions <> ?)
                 LIMIT ?
                """, (rs, rowNum) -> new MissingChunk(
                rs.getLong("id"), rs.getString("content"), rs.getString("content_hash")),
                model, repoId, dimensions, limit);
    }

    /** 批量写入（已有则整行覆盖）。调用方保证 vectors 与 chunks 一一对应。 */
    public void upsertBatch(long repoId, String model, int dimensions,
                            List<MissingChunk> chunks, List<float[]> vectors) {
        jdbc.batchUpdate("""
                INSERT INTO chunk_embedding (repo_id, chunk_id, model, dimensions, content_hash, vector)
                VALUES (?, ?, ?, ?, ?, ?) AS new
                ON DUPLICATE KEY UPDATE dimensions = new.dimensions,
                                        content_hash = new.content_hash,
                                        vector = new.vector,
                                        created_at = CURRENT_TIMESTAMP
                """, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                ps.setLong(1, repoId);
                ps.setLong(2, chunks.get(i).chunkId());
                ps.setString(3, model);
                ps.setInt(4, dimensions);
                ps.setString(5, chunks.get(i).contentHash());
                ps.setBytes(6, encode(vectors.get(i)));
            }

            @Override
            public int getBatchSize() {
                return chunks.size();
            }
        });
    }

    /** 该仓库该模型下的全部向量（维度不符的行不返回 —— 它们会在下次补算时被覆盖）。 */
    public List<StoredVector> loadAll(long repoId, String model, int dimensions) {
        return jdbc.query("""
                SELECT chunk_id, vector FROM chunk_embedding
                 WHERE repo_id = ? AND model = ? AND dimensions = ?
                """, (rs, rowNum) -> new StoredVector(
                rs.getLong("chunk_id"), decode(rs.getBytes("vector"))),
                repoId, model, dimensions);
    }

    /** float[] → 小端字节（跨语言读这个 BLOB 时，小端是 x86 的默认序，最不容易踩坑）。 */
    static byte[] encode(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vector) {
            buffer.putFloat(v);
        }
        return buffer.array();
    }

    static float[] decode(byte[] bytes) {
        if (bytes == null || bytes.length % Float.BYTES != 0) {
            throw new IllegalStateException("向量数据损坏（长度 " + (bytes == null ? 0 : bytes.length) + "）");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }
}
