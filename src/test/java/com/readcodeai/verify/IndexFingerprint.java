package com.readcodeai.verify;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 一个仓库索引结果的**结构指纹**：按 id 顺序的 {@code 类型:名字:文件:起始行:父键} 序列 + 各表计数。
 *
 * <p>为什么需要它：id 本身每次索引都会变（自增），所以不能直接比 id；
 * 但**顺序里的内容**必须稳定 —— 它同时反映了三件事：
 * 符号的输出顺序（父先于子）、同名符号的覆盖规则、各表落库条数。
 * 优化落库路径（批量化、并行解析）时，这是"只改速度、不改结果"的硬证据。
 */
public final class IndexFingerprint {

    private IndexFingerprint() {
    }

    public record Fingerprint(List<String> symbols, int calls, int relations, int chunks, int parentNotNull) {

        /** 便于打印成一行对比。 */
        public String summary() {
            return "符号 " + symbols.size() + " · 调用边 " + calls + " · 类型关系 " + relations
                    + " · 代码块 " + chunks + " · 有父键的符号 " + parentNotNull;
        }
    }

    public static Fingerprint of(JdbcTemplate jdbc, long repoId) {
        List<String> symbols = jdbc.query("""
                SELECT s.kind, s.name, f.path, s.start_line, COALESCE(p.qualified_name, '-') AS parent
                  FROM `symbol` s
                  JOIN `source_file` f ON f.id = s.file_id
                  LEFT JOIN `symbol` p ON p.id = s.parent_id
                 WHERE s.repo_id = ?
                 ORDER BY s.id
                """, (rs, rowNum) -> rs.getString(1) + ":" + rs.getString(2) + ":" + rs.getString(3)
                + ":" + rs.getInt(4) + ":" + rs.getString(5), repoId);
        return new Fingerprint(symbols, count(jdbc, "call_edge", repoId), count(jdbc, "type_relation", repoId),
                count(jdbc, "chunk", repoId), parentCount(jdbc, repoId));
    }

    private static int count(JdbcTemplate jdbc, String table, long repoId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM `" + table + "` WHERE repo_id = ?",
                Integer.class, repoId);
        return count == null ? 0 : count;
    }

    private static int parentCount(JdbcTemplate jdbc, long repoId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `symbol` WHERE repo_id = ? AND parent_id IS NOT NULL",
                Integer.class, repoId);
        return count == null ? 0 : count;
    }
}
