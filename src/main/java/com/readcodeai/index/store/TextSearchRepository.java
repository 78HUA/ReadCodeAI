package com.readcodeai.index.store;

import com.readcodeai.retrieve.model.ChunkHit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 第 2 层检索的数据访问：全文检索。
 *
 * <p><b>必须用布尔短语检索（双引号），不能用自然语言模式。</b>
 * 索引用的是 ngram 解析器（为了能搜中文注释），而 ngram 会把标识符切成二元组，
 * 自然语言模式下 {@code loginCheck} 能命中 300+ 条全是噪声；
 * 加上双引号变成短语检索后，命中数与 {@code LIKE} 人工基准**完全一致**。
 * 这是实测出来的结论，见 {@code docs/verification-log.md}。
 */
@Repository
public class TextSearchRepository {

    /**
     * 短语检索。
     *
     * @param requireAll true = 每个短语都必须命中（精确，用于短查询）；
     *                   false = 任一片语命中即可（放宽，用于自然语言长句）
     */
    public List<ChunkHit> searchPhrases(long repoId, List<String> phrases, boolean requireAll, int limit) {
        if (phrases.isEmpty()) {
            return List.of();
        }
        String booleanQuery = phrases.stream()
                .map(phrase -> (requireAll ? "+" : "") + "\"" + phrase.replace("\"", "") + "\"")
                .reduce((a, b) -> a + " " + b)
                .orElseThrow();

        return jdbc.query("""
                SELECT c.id, c.kind, c.start_line, c.end_line, c.token_estimate, c.content,
                       f.path AS file_path,
                       s.id AS symbol_id, s.qualified_name AS symbol_qname,
                       MATCH(c.content) AGAINST(? IN BOOLEAN MODE) AS score
                  FROM `chunk` c
                  JOIN `source_file` f ON f.id = c.file_id
                  LEFT JOIN `symbol` s ON s.id = c.symbol_id
                 WHERE c.repo_id = ? AND MATCH(c.content) AGAINST(? IN BOOLEAN MODE)
                 ORDER BY score DESC, c.id
                 LIMIT ?
                """, (rs, rowNum) -> new ChunkHit(
                rs.getLong("id"), rs.getString("kind"), rs.getString("file_path"),
                rs.getInt("start_line"), rs.getInt("end_line"),
                rs.getObject("symbol_id") == null ? null : rs.getLong("symbol_id"),
                rs.getString("symbol_qname"), rs.getInt("token_estimate"),
                rs.getDouble("score"), rs.getString("content")),
                booleanQuery, repoId, booleanQuery, limit);
    }

    private final JdbcTemplate jdbc;

    public TextSearchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 一个仓库全部 SYMBOL 类 chunk 的元数据（带内容）—— 向量检索用它做候选池。
     *
     * <p>放在这里而不是新开一个仓库类：全文检索与向量检索读的是**同一份 chunk 元数据**，
     * 分两个类只会让两边的字段慢慢长歪。全量加载的规模判据见 {@code ChunkEmbeddingRepository}。
     */
    public List<ChunkHit> selectSymbolChunks(long repoId) {
        return jdbc.query("""
                SELECT c.id, c.kind, c.start_line, c.end_line, c.token_estimate, c.content,
                       f.path AS file_path,
                       s.id AS symbol_id, s.qualified_name AS symbol_qname
                  FROM `chunk` c
                  JOIN `source_file` f ON f.id = c.file_id
                  LEFT JOIN `symbol` s ON s.id = c.symbol_id
                 WHERE c.repo_id = ? AND c.kind = 'SYMBOL'
                 ORDER BY c.id
                """, (rs, rowNum) -> new ChunkHit(
                rs.getLong("id"), rs.getString("kind"), rs.getString("file_path"),
                rs.getInt("start_line"), rs.getInt("end_line"),
                rs.getObject("symbol_id") == null ? null : rs.getLong("symbol_id"),
                rs.getString("symbol_qname"), rs.getInt("token_estimate"),
                0, rs.getString("content")), repoId);
    }
}
