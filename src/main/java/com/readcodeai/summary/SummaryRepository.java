package com.readcodeai.summary;

import com.readcodeai.summary.model.RepoSummary.Ranked;
import com.readcodeai.summary.model.RepoSummary.SymbolRef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

/**
 * 摘要里**结构部分**的数据来源：全部是聚合查询，没有一处调用模型。
 *
 * <p>这一层是「能算准的别猜」在"总结"这件事上的落地 ——
 * 模块划分、规模、入口、枢纽、实现关系、没有调用者的类，都是查出来的事实。
 * 模型的活只剩一句"这个模块大致负责什么"，而且那句话还要回来核对。
 */
@Repository
public class SummaryRepository {

    private static final String SYMBOL_COLUMNS = """
            s.id, s.kind, s.name, s.qualified_name, s.signature,
            s.start_line, s.end_line, s.modifiers, s.return_type, s.parent_id, f.path AS file_path
            """;

    /** 与抽查基准一致：getter/setter 这类过于简单的目标不参与排行，否则榜单毫无信息量。 */
    private static final String TRIVIAL_FILTER = """
            AND s.name NOT LIKE 'get%%' AND s.name NOT LIKE 'set%%' AND s.name NOT LIKE 'is%%'
            AND s.name NOT IN ('toString','equals','hashCode','main','close','flush','run','apply','accept','compareTo')
            """;

    private final JdbcTemplate jdbc;

    public SummaryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 各 kind 的符号数（规模指标用）。 */
    public ScaleVitals vitals(long repoId) {
        List<ScaleVitals> rows = jdbc.query("""
                SELECT SUM(kind IN ('CLASS','ENUM','RECORD')) AS class_count,
                       SUM(kind = 'INTERFACE')                AS interface_count,
                       SUM(kind = 'METHOD')                   AS method_count,
                       COUNT(*)                               AS total
                  FROM `symbol` WHERE repo_id = ?
                """, (rs, rowNum) -> new ScaleVitals(
                rs.getInt("class_count"), rs.getInt("interface_count"),
                rs.getInt("method_count"), rs.getInt("total")), repoId);
        return rows.isEmpty() ? new ScaleVitals(0, 0, 0, 0) : rows.get(0);
    }

    public record ScaleVitals(int classCount, int interfaceCount, int methodCount, int totalSymbols) {
    }

    /**
     * 入口类：含 {@code static main} 的类型。
     *
     * <p><b>静态能认出来的入口只有这一种</b>：Servlet、Filter、Spring 的 Controller、
     * 被框架反射回调的类，都要看注解或外部配置才知道是入口 —— 那些我们暂时认不出，
     * 所以这里的入口清单是"至少这些"，不是"只有这些"。这一点在返回里也如实写着。
     */
    public List<SymbolRef> entryPoints(long repoId, int limit) {
        return jdbc.query("""
                SELECT %s
                  FROM `symbol` s
                  JOIN `symbol` m ON m.parent_id = s.id AND m.kind = 'METHOD' AND m.name = 'main'
                                  AND m.modifiers LIKE '%%static%%'
                  JOIN `source_file` f ON f.id = s.file_id
                 WHERE s.repo_id = ?
                 GROUP BY s.id
                 ORDER BY f.path
                 LIMIT ?
                """.formatted(SYMBOL_COLUMNS),
                (rs, rowNum) -> toSymbolRef(rs), repoId, limit);
    }

    /**
     * 调用枢纽：**被调用最多的类型**（把指向该类方法的调用边汇总到类上）。
     *
     * <p>为什么汇总到类：单个方法被调 50 次可能只是因为它是个工具函数；
     * 而"这个类是整个仓库的中枢"才是读者要的信息。
     */
    public List<Ranked> callHubs(long repoId, int limit) {
        return jdbc.query("""
                SELECT %s, COUNT(*) AS cnt
                  FROM `call_edge` ce
                  JOIN `symbol` callee ON callee.id = ce.callee_symbol_id
                  JOIN `symbol` s ON s.id = COALESCE(callee.parent_id, callee.id)
                  JOIN `source_file` f ON f.id = s.file_id
                 WHERE ce.repo_id = ? AND ce.resolved = 1
                 GROUP BY s.id
                 ORDER BY cnt DESC, s.qualified_name
                 LIMIT ?
                """.formatted(SYMBOL_COLUMNS),
                (rs, rowNum) -> new Ranked(toSymbolRef(rs), rs.getInt("cnt")), repoId, limit);
    }

    public List<Ranked> topMethods(long repoId, int limit) {
        return jdbc.query("""
                SELECT %s, COUNT(*) AS cnt
                  FROM `call_edge` ce
                  JOIN `symbol` s ON s.id = ce.callee_symbol_id
                  JOIN `source_file` f ON f.id = s.file_id
                 WHERE ce.repo_id = ? AND ce.resolved = 1 AND s.kind = 'METHOD'
                 %s
                 GROUP BY s.id
                 ORDER BY cnt DESC, s.qualified_name
                 LIMIT ?
                """.formatted(SYMBOL_COLUMNS, TRIVIAL_FILTER),
                (rs, rowNum) -> new Ranked(toSymbolRef(rs), rs.getInt("cnt")), repoId, limit);
    }

    /** 实现关系：直接子类型最多的接口/父类。 */
    public List<Ranked> implementations(long repoId, int limit) {
        return jdbc.query("""
                SELECT %s, COUNT(*) AS cnt
                  FROM `type_relation` tr
                  JOIN `symbol` s ON s.id = tr.super_symbol_id
                  JOIN `source_file` f ON f.id = s.file_id
                 WHERE tr.repo_id = ? AND tr.kind = 'IMPLEMENTS' AND tr.resolved = 1
                 GROUP BY s.id
                 ORDER BY cnt DESC, s.qualified_name
                 LIMIT ?
                """.formatted(SYMBOL_COLUMNS),
                (rs, rowNum) -> new Ranked(toSymbolRef(rs), rs.getInt("cnt")), repoId, limit);
    }

    /**
     * 没有任何调用者的类。
     *
     * <p><b>只列出来，不判定</b>：这类类可能是入口（{@code main}）、可能是框架通过反射/注解回调的、
     * 也可能是真的死代码 —— **静态分析分不出这三种**。把它当成"死代码清单"会误导人，
     * 所以这里只做"没有任何仓库内调用"这个事实陈述。
     */
    public List<SymbolRef> uncalledClasses(long repoId, int limit) {
        return jdbc.query("""
                SELECT %s
                  FROM `symbol` s
                  JOIN `source_file` f ON f.id = s.file_id
                 WHERE s.repo_id = ? AND s.kind IN ('CLASS','ENUM','RECORD')
                   AND NOT EXISTS (SELECT 1
                                     FROM `symbol` m
                                     JOIN `call_edge` ce ON ce.callee_symbol_id = m.id AND ce.resolved = 1
                                    WHERE m.parent_id = s.id AND m.kind = 'METHOD' AND m.name <> 'main')
                 ORDER BY f.path
                 LIMIT ?
                """.formatted(SYMBOL_COLUMNS),
                (rs, rowNum) -> toSymbolRef(rs), repoId, limit);
    }

    /**
     * 模块的代表性类型：按"类里有多少方法"排 —— 方法多通常意味着这个类更有内容。
     *
     * <p>接的是**正则**而不是 LIKE，因为两种模块要的东西不一样：
     * 「根包」只要本包里的类型（LIKE 会把子包全吞进来），而具名模块要**整棵子树**。
     * 正则由 {@link RepoSummaryService} 拼好（包名里的点会被转义成 {@code [.]}）。
     */
    public List<SymbolRef> keyTypes(long repoId, String regex, int limit) {
        if (regex == null || regex.isBlank()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT %s, COUNT(m.id) AS members
                  FROM `symbol` s
                  LEFT JOIN `symbol` m ON m.parent_id = s.id AND m.kind IN ('METHOD','CONSTRUCTOR')
                  JOIN `source_file` f ON f.id = s.file_id
                 WHERE s.repo_id = ? AND s.parent_id IS NULL
                   AND s.qualified_name REGEXP ?
                 GROUP BY s.id
                 ORDER BY members DESC, s.qualified_name
                 LIMIT ?
                """.formatted(SYMBOL_COLUMNS),
                (rs, rowNum) -> toSymbolRef(rs), repoId, regex, limit);
    }

    /** 按名字精确查符号（语义部分核对用）：**只认精确同名**，模糊匹配会把"编的名字"也放过。 */
    public List<String> exactNames(long repoId, List<String> names) {
        if (names.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(names.size(), "?"));
        Object[] args = new Object[names.size() + 1];
        args[0] = repoId;
        for (int i = 0; i < names.size(); i++) {
            args[i + 1] = names.get(i);
        }
        return jdbc.queryForList("""
                SELECT DISTINCT name FROM `symbol` WHERE repo_id = ? AND name IN (%s)
                """.formatted(placeholders), String.class, args);
    }

    /** 每个源文件一行：路径、行数、它的顶层类型限定名、文件里的符号数（模块归并的原始数据）。 */
    public List<FileScale> fileScales(long repoId) {
        return jdbc.query("""
                SELECT f.path, f.loc, s.qualified_name AS type_qname,
                       (SELECT COUNT(*) FROM `symbol` x WHERE x.file_id = f.id) AS symbol_count
                  FROM `source_file` f
                  LEFT JOIN `symbol` s ON s.file_id = f.id AND s.parent_id IS NULL
                 WHERE f.repo_id = ?
                 ORDER BY f.path
                """, (rs, rowNum) -> new FileScale(
                rs.getString("path"), rs.getInt("loc"),
                rs.getString("type_qname"), rs.getInt("symbol_count")), repoId);
    }

    public record FileScale(String path, int loc, String typeQualifiedName, int symbolCount) {
    }

    private static SymbolRef toSymbolRef(ResultSet rs) throws SQLException {
        return new SymbolRef(
                rs.getLong("id"), rs.getString("kind"), rs.getString("qualified_name"),
                rs.getString("signature"), rs.getString("file_path"),
                rs.getInt("start_line"), rs.getInt("end_line"));
    }
}
