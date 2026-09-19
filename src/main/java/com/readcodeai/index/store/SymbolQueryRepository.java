package com.readcodeai.index.store;

import com.readcodeai.retrieve.model.CallSiteView;
import com.readcodeai.retrieve.model.RepoView;
import com.readcodeai.retrieve.model.SymbolView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 第 1 层检索的数据访问：定位、调用关系、类型层次。
 *
 * <p>全部是**确定性查询**（查表 / 图查询），没有任何相似度计算 ——
 * 「能算准的别猜」这条纪律在这一层体现得最直接。
 */
@Repository
public class SymbolQueryRepository {

    private static final String SYMBOL_COLUMNS = """
            s.id, s.kind, s.name, s.qualified_name, s.signature,
            s.start_line, s.end_line, s.modifiers, s.return_type, s.parent_id, f.path AS file_path
            """;

    /** 抽查基准要避开 getter/setter 这类过于简单的目标，否则测不出问题。 */
    private static final String TRIVIAL_NAMES = """
            ('toString','equals','hashCode','main','close','flush','run','apply','accept','compareTo')
            """;

    private final JdbcTemplate jdbc;

    public SymbolQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 被调用最多的方法 —— 抽查清单与集成测试都从这里取基准，
     * 这样测试**不绑定任何具体项目**，换测试仓库不用改代码。
     */
    public List<SymbolView> mostCalledMethods(long repoId, int limit) {
        return jdbc.query("""
                SELECT %s
                  FROM `symbol` s
                  JOIN `source_file` f ON f.id = s.file_id
                  JOIN (SELECT callee_symbol_id, COUNT(*) AS c
                          FROM `call_edge`
                         WHERE repo_id = ? AND resolved = 1
                         GROUP BY callee_symbol_id) cnt ON cnt.callee_symbol_id = s.id
                 WHERE s.repo_id = ? AND s.kind = 'METHOD'
                   AND s.name NOT LIKE 'get%%' AND s.name NOT LIKE 'set%%' AND s.name NOT LIKE 'is%%'
                   AND s.name NOT IN %s
                 ORDER BY cnt.c DESC, s.id
                 LIMIT ?
                """.formatted(SYMBOL_COLUMNS, TRIVIAL_NAMES),
                (rs, rowNum) -> toSymbolView(rs), repoId, repoId, limit);
    }

    /** 实现类最多的接口 —— 同上，给测试与抽查提供不写死的基准。 */
    public List<SymbolView> mostImplementedInterfaces(long repoId, int limit) {
        return jdbc.query("""
                SELECT %s
                  FROM `symbol` s
                  JOIN `source_file` f ON f.id = s.file_id
                  JOIN (SELECT super_symbol_id, COUNT(*) AS c
                          FROM `type_relation`
                         WHERE repo_id = ? AND resolved = 1
                         GROUP BY super_symbol_id) cnt ON cnt.super_symbol_id = s.id
                 WHERE s.repo_id = ? AND s.kind = 'INTERFACE'
                 ORDER BY cnt.c DESC, s.id
                 LIMIT ?
                """.formatted(SYMBOL_COLUMNS),
                (rs, rowNum) -> toSymbolView(rs), repoId, repoId, limit);
    }

    public List<RepoView> listRepos() {
        return jdbc.query("""
                SELECT id, name, root_path, commit_hash, file_count, parsed_ok_count, total_loc,
                       symbol_count, call_edge_count, call_resolved_count, status, indexed_at
                  FROM `repo`
                 ORDER BY indexed_at DESC, id DESC
                """, (rs, rowNum) -> new RepoView(
                rs.getLong("id"), rs.getString("name"), rs.getString("root_path"),
                rs.getString("commit_hash"), rs.getInt("file_count"), rs.getInt("parsed_ok_count"),
                rs.getInt("total_loc"), rs.getInt("symbol_count"), rs.getInt("call_edge_count"),
                rs.getInt("call_resolved_count"), rs.getString("status"),
                rs.getTimestamp("indexed_at") == null ? null : rs.getTimestamp("indexed_at").toLocalDateTime()));
    }

    /** 最近一次索引完成的仓库；没有就返回 null（调用方负责报错，不静默兜底）。 */
    public Long latestReadyRepoId() {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM `repo` WHERE status = 'READY' ORDER BY indexed_at DESC, id DESC LIMIT 1",
                Long.class);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * 按仓库根路径查 —— 「最近索引的仓库」是个会变的全局状态，
     * 测试与自动化脚本必须能指名道姓地锁定自己要的那个语料，不能依赖它。
     */
    public java.util.Optional<RepoView> findByRootPath(String rootPath) {
        List<RepoView> found = jdbc.query("""
                SELECT id, name, root_path, commit_hash, file_count, parsed_ok_count, total_loc,
                       symbol_count, call_edge_count, call_resolved_count, status, indexed_at
                  FROM `repo`
                 WHERE root_path = ?
                 ORDER BY id DESC
                 LIMIT 1
                """, (rs, rowNum) -> new RepoView(
                rs.getLong("id"), rs.getString("name"), rs.getString("root_path"),
                rs.getString("commit_hash"), rs.getInt("file_count"), rs.getInt("parsed_ok_count"),
                rs.getInt("total_loc"), rs.getInt("symbol_count"), rs.getInt("call_edge_count"),
                rs.getInt("call_resolved_count"), rs.getString("status"),
                rs.getTimestamp("indexed_at") == null ? null : rs.getTimestamp("indexed_at").toLocalDateTime()),
                rootPath);
        return found.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(found.get(0));
    }

    /**
     * 定位：按简单名或限定名找符号。排序把「精确匹配」排在前面 ——
     * 用户搜 {@code upload} 时，叫 upload 的方法应该比 {@code uploadMusic} 先出现。
     */
    public List<SymbolView> findSymbols(long repoId, String keyword, int limit) {
        return jdbc.query("""
                SELECT %s
                  FROM `symbol` s JOIN `source_file` f ON f.id = s.file_id
                 WHERE s.repo_id = ? AND (s.name = ? OR s.qualified_name LIKE ?)
                 ORDER BY CASE WHEN s.name = ? THEN 0
                               WHEN s.qualified_name = ? THEN 1
                               ELSE 2 END,
                          LENGTH(s.qualified_name), s.qualified_name
                 LIMIT ?
                """.formatted(SYMBOL_COLUMNS),
                (rs, rowNum) -> toSymbolView(rs),
                repoId, keyword, "%" + keyword + "%", keyword, keyword, limit);
    }

    /**
     * 索引时记录的文件内容哈希。
     *
     * <p>用途只有一个但很重要：把它与磁盘当前内容的哈希一比，就知道**这个文件在索引之后有没有被改过**
     * —— 改过就意味着行号可能漂移，界面上必须提示（见 FileContentService）。
     */
    public java.util.Optional<String> contentHash(long repoId, String path) {
        List<String> hashes = jdbc.queryForList("SELECT content_hash FROM `source_file` WHERE repo_id = ? AND path = ?",
                String.class, repoId, path);
        return hashes.isEmpty() || hashes.get(0) == null
                ? java.util.Optional.empty() : java.util.Optional.of(hashes.get(0));
    }

    /** 按 id 取仓库 —— 证据校验需要仓库根路径，才能把「相对路径」还原成磁盘上的真实文件。 */
    public java.util.Optional<RepoView> findRepoById(long repoId) {
        List<RepoView> found = jdbc.query("""
                SELECT id, name, root_path, commit_hash, file_count, parsed_ok_count, total_loc,
                       symbol_count, call_edge_count, call_resolved_count, status, indexed_at
                  FROM `repo`
                 WHERE id = ?
                """, (rs, rowNum) -> new RepoView(
                rs.getLong("id"), rs.getString("name"), rs.getString("root_path"),
                rs.getString("commit_hash"), rs.getInt("file_count"), rs.getInt("parsed_ok_count"),
                rs.getInt("total_loc"), rs.getInt("symbol_count"), rs.getInt("call_edge_count"),
                rs.getInt("call_resolved_count"), rs.getString("status"),
                rs.getTimestamp("indexed_at") == null ? null : rs.getTimestamp("indexed_at").toLocalDateTime()),
                repoId);
        return found.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(found.get(0));
    }

    /**
     * 在指定类型里按名字找成员 —— 「XxxService 的 read 方法」这类**限定查找**靠它。
     *
     * <p>为什么必须有它：裸方法名往往不唯一（gson 里有一堆 {@code read}），
     * 不限定在某个类里就只能挑到"第一个同名的"，那是猜不是查。
     */
    public List<SymbolView> findMembersInType(long repoId, String typeQualifiedName, String name) {
        return jdbc.query("""
                SELECT %s
                  FROM `symbol` s
                  JOIN `source_file` f ON f.id = s.file_id
                  JOIN `symbol` owner ON owner.id = s.parent_id
                 WHERE s.repo_id = ? AND owner.qualified_name = ? AND s.name = ?
                 ORDER BY LENGTH(s.qualified_name), s.qualified_name
                """.formatted(SYMBOL_COLUMNS),
                (rs, rowNum) -> toSymbolView(rs), repoId, typeQualifiedName, name);
    }

    /**
     * 一个类型的直接成员（方法/字段/构造器）—— 结构题（"这个类有哪些方法"）靠它。
     */
    public List<SymbolView> children(long parentSymbolId) {
        return jdbc.query("""
                SELECT %s
                  FROM `symbol` s JOIN `source_file` f ON f.id = s.file_id
                 WHERE s.parent_id = ?
                 ORDER BY s.kind, s.start_line
                """.formatted(SYMBOL_COLUMNS),
                (rs, rowNum) -> toSymbolView(rs), parentSymbolId);
    }

    public SymbolView findSymbolById(long symbolId) {        List<SymbolView> found = jdbc.query("""
                SELECT %s
                  FROM `symbol` s JOIN `source_file` f ON f.id = s.file_id
                 WHERE s.id = ?
                """.formatted(SYMBOL_COLUMNS),
                (rs, rowNum) -> toSymbolView(rs), symbolId);
        return found.isEmpty() ? null : found.get(0);
    }

    /** 谁调用了它。按文件与行号排序，让同一处的多个调用挨着，便于人工核对。 */
    public List<CallSiteView> callers(long symbolId) {
        return jdbc.query("""
                SELECT caller.id AS caller_id, caller.name AS caller_name,
                       caller.qualified_name AS caller_qname, caller.signature AS caller_signature,
                       f.path AS caller_file,
                       ce.call_line, ce.call_kind, ce.callee_raw, ce.resolved, ce.reason
                  FROM `call_edge` ce
                  JOIN `symbol` caller ON caller.id = ce.caller_symbol_id
                  JOIN `source_file` f ON f.id = caller.file_id
                 WHERE ce.callee_symbol_id = ?
                 ORDER BY f.path, ce.call_line
                """, (rs, rowNum) -> new CallSiteView(
                rs.getLong("caller_id"), rs.getString("caller_name"), rs.getString("caller_qname"),
                rs.getString("caller_signature"), rs.getString("caller_file"),
                rs.getString("caller_file"), rs.getInt("call_line"), rs.getString("call_kind"),
                rs.getString("callee_raw"), rs.getBoolean("resolved"), rs.getString("reason")), symbolId);
    }

    /** 我调用了谁。未解析的边也返回，但 resolved=false + reason，调用方可自行决定要不要展示。 */
    public List<CallSiteView> callees(long symbolId) {
        return jdbc.query("""
                SELECT callee.id AS callee_id, callee.name AS callee_name,
                       callee.qualified_name AS callee_qname, callee.signature AS callee_signature,
                       cf.path AS callee_file,
                       caller_file.path AS call_site_file,
                       ce.call_line, ce.call_kind, ce.callee_raw, ce.resolved, ce.reason
                  FROM `call_edge` ce
                  JOIN `symbol` caller ON caller.id = ce.caller_symbol_id
                  JOIN `source_file` caller_file ON caller_file.id = caller.file_id
                  LEFT JOIN `symbol` callee ON callee.id = ce.callee_symbol_id
                  LEFT JOIN `source_file` cf ON cf.id = callee.file_id
                 WHERE ce.caller_symbol_id = ?
                 ORDER BY ce.call_line
                """, (rs, rowNum) -> new CallSiteView(
                rs.getObject("callee_id") == null ? null : rs.getLong("callee_id"),
                rs.getString("callee_name"), rs.getString("callee_qname"),
                rs.getString("callee_signature"), rs.getString("callee_file"),
                rs.getString("call_site_file"), rs.getInt("call_line"), rs.getString("call_kind"),
                rs.getString("callee_raw"), rs.getBoolean("resolved"), rs.getString("reason")), symbolId);
    }

    /**
     * 有哪些实现 / 子类。**只返回直接关系** —— 传递闭包留给调用方决定
     * （直接关系是确定的，闭包要不要展开取决于用户问的是什么）。
     */
    public List<SymbolView> directImplementations(long symbolId) {
        return jdbc.query("""
                SELECT %s, tr.kind AS relation_kind
                  FROM `type_relation` tr
                  JOIN `symbol` s ON s.id = tr.sub_symbol_id
                  JOIN `source_file` f ON f.id = s.file_id
                 WHERE tr.super_symbol_id = ?
                 ORDER BY s.qualified_name
                """.formatted(SYMBOL_COLUMNS),
                (rs, rowNum) -> toSymbolView(rs), symbolId);
    }

    private static SymbolView toSymbolView(ResultSet rs) throws SQLException {
        return new SymbolView(
                rs.getLong("id"), rs.getString("kind"), rs.getString("name"),
                rs.getString("qualified_name"), rs.getString("signature"),
                rs.getString("file_path"), rs.getInt("start_line"), rs.getInt("end_line"),
                rs.getString("modifiers"), rs.getString("return_type"),
                rs.getObject("parent_id") == null ? null : rs.getLong("parent_id"));
    }
}
