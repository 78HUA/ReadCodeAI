package com.readcodeai.index.store;

import com.readcodeai.index.model.CollectedCall;
import com.readcodeai.index.model.CollectedChunk;
import com.readcodeai.index.model.CollectedSymbol;
import com.readcodeai.index.model.CollectedTypeRelation;
import com.readcodeai.index.model.FileOutcome;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 索引结果落库。
 *
 * <p>刻意用 JdbcTemplate 手写 SQL 而不是 ORM：这些表就是为查询建的，SQL 更直白，
 * 也更好解释「为什么这么建索引」。
 *
 * <p><b>为什么 source_file / symbol 也要批量插入</b>：它们的量级不大（千级），早先逐条插入
 * 图的是"顺手拿到自增 id"。但实测（gson 2.3 万行）落库占整个索引耗时的 70%，
 * 逐条插入意味着**每条一次网络往返 + 一次自动提交**（无事务时每次 INSERT 都是一个事务）——
 * 这才是那 12 秒的来源。改成批量插入 + **一次 SELECT 回填 id 映射**，
 * 语义完全不变（同名符号"后者覆盖前者"的规则靠 SELECT 的 id 升序复现），往返从 N 次降到 2 次。
 */
@Repository
public class IndexRepository {

    private static final String INSERT_SYMBOL = """
            INSERT INTO `symbol`
              (repo_id, file_id, kind, name, qualified_name, signature, parent_id,
               start_line, end_line, modifiers, return_type, javadoc)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public IndexRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 同一个路径重复索引 = 覆盖：先删旧记录（外键级联会带走子表），再插一条 INDEXING 状态的新行。 */
    public long beginRepo(String name, String rootPath, String commitHash) {
        jdbc.update("DELETE FROM `repo` WHERE root_path = ?", rootPath);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO `repo` (name, root_path, commit_hash, status, created_at)
                    VALUES (?, ?, ?, 'INDEXING', ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, rootPath);
            ps.setString(3, commitHash);
            ps.setTimestamp(4, Timestamp.valueOf(LocalDateTime.now()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入 repo 后拿不到自增主键");
        }
        return key.longValue();
    }

    /**
     * 批量插入文件记录，再**一次查回** 相对路径 -> id。
     *
     * <p>为什么不用 {@code GeneratedKeyHolder} 逐条拿 id：那正是慢的来源（每条一次往返+提交）。
     * 批量插入拿不到"逐行主键"，而按 {@code repo_id} 一次查回来既便宜又更稳 ——
     * 不需要赌驱动的批量自增回填行为。
     */
    public Map<String, Long> insertSourceFiles(long repoId, List<FileOutcome> files) {
        return insertFiles(repoId, files, "JAVA");
    }

    /**
     * 插入**文本文件**的行（{@code kind='TEXT'}）。
     *
     * <p>为什么要单独一行方法而不是复用：这两类行的语义完全不同 ——
     * JAVA 行参与"解析成功率"与模块划分，TEXT 行只是"这份文件里的文本可以被搜到"。
     * 混在一张表里没关系（chunk 的外键需要一个文件行），但**必须能分得开**，
     * 否则摘要页的"文件数 / 模块划分"会把 pom.xml、README.md 也算进去，数字就说不清了。
     */
    public Map<String, Long> insertTextFiles(long repoId, List<FileOutcome> files) {
        return insertFiles(repoId, files, "TEXT");
    }

    /**
     * 重建 `chunk` 的全文索引（`OPTIMIZE TABLE`）。
     *
     * <p><b>为什么必须有这一步</b>：索引是"删了再插"的覆盖语义，每次重索引都会往 InnoDB 的
     * ngram 全文索引里留碎片。实测（2026-09-20）：一天里重复索引几十次之后，
     * 同一个检索从 **65 ms 涨到 13,355 ms**（最坏一次 99 秒）—— 因为查询要合并大量索引分段。
     * 重建一次只要几百毫秒，把这个 200 倍的退化清掉。
     *
     * <p>为什么放在**索引收尾**而不是定时任务：碎片正是索引过程产生的，产生完立刻收拾最自然；
     * 而且只在"这次索引的检索单元数超过阈值"时才做（小仓库不值得付这个代价，
     * 大仓库正是最需要的地方）。
     *
     * <p>注：MySQL 的 `innodb_optimize_fulltext_only` 是 GLOBAL 变量（要额外权限），
     * 所以这里用普通权限就能执行的 `OPTIMIZE TABLE`；它同时会重建整张表 ——
     * 只对"有几百个以上检索单元"的仓库执行，就是这个原因。
     */
    public void optimizeFulltextIndex() {
        jdbc.execute("OPTIMIZE TABLE `chunk`");
    }

    private Map<String, Long> insertFiles(long repoId, List<FileOutcome> files, String kind) {
        if (files.isEmpty()) {
            return Map.of();
        }
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.batchUpdate("""
                INSERT INTO `source_file`
                  (repo_id, path, kind, content_hash, loc, parsed_ok, parse_error, indexed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                FileOutcome file = files.get(i);
                ps.setLong(1, repoId);
                ps.setString(2, file.relativePath());
                ps.setString(3, kind);
                ps.setString(4, file.contentHash());
                ps.setInt(5, file.loc());
                ps.setBoolean(6, file.parsedOk());
                ps.setString(7, file.errorMessage());
                ps.setTimestamp(8, now);
            }

            @Override
            public int getBatchSize() {
                return files.size();
            }
        });

        Map<String, Long> ids = new HashMap<>(files.size() * 2);
        jdbc.query("SELECT id, path FROM `source_file` WHERE repo_id = ?",
                rs -> {
                    ids.put(rs.getString("path"), rs.getLong("id"));
                }, repoId);
        return ids;
    }

    /**
     * 批量插入符号，再**一次查回** 检索键 -> id，最后批量回填 parent_id。
     *
     * <p>父 id 不再依赖"父符号必须排在前、边插边查"（那是逐条插时代的写法），
     * 而是插完再回填一次 —— 顺序依赖消失，语义不变（{@code analyze} 本来也保证了父先于子）。
     *
     * <p>同名符号"后者覆盖前者"的规则靠 SELECT 的 **id 升序**复现：id 由批量插入的语句顺序决定，
     * 与旧版逐条插入完全一致。
     *
     * @return 符号检索键 -> symbol.id
     */
    public Map<String, Long> insertSymbols(long repoId, List<CollectedSymbol> symbols,
                                           Map<String, Long> fileIds) {
        List<CollectedSymbol> insertable = symbols.stream()
                .filter(symbol -> fileIds.containsKey(symbol.filePath()))
                .toList();
        if (insertable.isEmpty()) {
            return Map.of();
        }
        jdbc.batchUpdate(INSERT_SYMBOL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                CollectedSymbol symbol = insertable.get(i);
                ps.setLong(1, repoId);
                ps.setLong(2, fileIds.get(symbol.filePath()));
                ps.setString(3, symbol.kind());
                ps.setString(4, symbol.name());
                ps.setString(5, symbol.qualifiedName());
                ps.setString(6, symbol.signature());
                // parent_id 全部先留空：父子关系在下面用一次批量 UPDATE 回填
                ps.setNull(7, java.sql.Types.BIGINT);
                ps.setInt(8, symbol.startLine());
                ps.setInt(9, symbol.endLine());
                ps.setString(10, symbol.modifiers());
                ps.setString(11, symbol.returnType());
                ps.setString(12, symbol.javadoc());
            }

            @Override
            public int getBatchSize() {
                return insertable.size();
            }
        });

        Map<String, Long> ids = new HashMap<>(insertable.size() * 2);
        jdbc.query("SELECT id, qualified_name FROM `symbol` WHERE repo_id = ? ORDER BY id",
                rs -> {
                    ids.put(rs.getString("qualified_name"), rs.getLong("id"));
                }, repoId);

        List<CollectedSymbol> withParent = insertable.stream()
                .filter(symbol -> symbol.parentQualifiedName() != null
                        && ids.containsKey(symbol.parentQualifiedName()))
                .toList();
        if (!withParent.isEmpty()) {
            // **按自然键定位到行本身**（仓库 + 文件 + 限定名 + 起始行），而不是 `WHERE id = ids.get(qualified_name)`：
            // 限定名可能重复（同名符号是允许的，id 映射就是"后者覆盖前者"），用 id 映射定位会把两行都更到同一个 id 上，
            // 另一行的 parent_id 永远留在 NULL —— 摘要页的"每个文件只算一次"依赖 parent_id IS NULL 判顶层类型，
            // 于是文件被重复计数（这个 bug 是全量测试里的摘要用例抓出来的，不是单测）。
            jdbc.batchUpdate("""
                    UPDATE `symbol` SET parent_id = ?
                     WHERE repo_id = ? AND file_id = ? AND qualified_name = ? AND start_line = ?
                    """, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    CollectedSymbol symbol = withParent.get(i);
                    ps.setLong(1, ids.get(symbol.parentQualifiedName()));
                    ps.setLong(2, repoId);
                    ps.setLong(3, fileIds.get(symbol.filePath()));
                    ps.setString(4, symbol.qualifiedName());
                    ps.setInt(5, symbol.startLine());
                }

                @Override
                public int getBatchSize() {
                    return withParent.size();
                }
            });
        }
        return ids;
    }

    public void insertCalls(long repoId, List<CollectedCall> calls, Map<String, Long> symbolIds) {
        jdbc.batchUpdate("""
                        INSERT INTO `call_edge`
                          (repo_id, caller_symbol_id, callee_symbol_id, callee_raw, call_line, call_kind, resolved, reason)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                calls, calls.size(), (ps, call) -> {
                    Long callerId = symbolIds.get(call.callerSymbolKey());
                    if (callerId == null) {
                        throw new IllegalStateException("调用边找不到调用者符号：" + call.callerSymbolKey());
                    }
                    ps.setLong(1, repoId);
                    ps.setLong(2, callerId);
                    Long calleeId = call.calleeSymbolKey() == null ? null : symbolIds.get(call.calleeSymbolKey());
                    if (calleeId == null) {
                        ps.setNull(3, java.sql.Types.BIGINT);
                    } else {
                        ps.setLong(3, calleeId);
                    }
                    ps.setString(4, call.calleeRaw());
                    ps.setInt(5, call.line());
                    ps.setString(6, call.callKind());
                    ps.setBoolean(7, call.resolved());
                    ps.setString(8, call.reason());
                });
    }

    public void insertRelations(long repoId, List<CollectedTypeRelation> relations, Map<String, Long> symbolIds) {        jdbc.batchUpdate("""
                        INSERT INTO `type_relation`
                          (repo_id, sub_symbol_id, super_raw, super_symbol_id, kind, resolved, external)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                relations, relations.size(), (ps, relation) -> {
                    Long subId = symbolIds.get(relation.subSymbolKey());
                    if (subId == null) {
                        throw new IllegalStateException("类型关系找不到子类型符号：" + relation.subSymbolKey());
                    }
                    ps.setLong(1, repoId);
                    ps.setLong(2, subId);
                    ps.setString(3, relation.superRaw());
                    Long superId = relation.superSymbolKey() == null ? null : symbolIds.get(relation.superSymbolKey());
                    if (superId == null) {
                        ps.setNull(4, java.sql.Types.BIGINT);
                    } else {
                        ps.setLong(4, superId);
                    }
                    ps.setString(5, relation.kind());
                    ps.setBoolean(6, relation.resolved());
                    ps.setBoolean(7, relation.external());
                });
    }

    /**
     * 插入全文检索单元。
     *
     * <p>批量大小限制在 200：chunk 的 content 是 MEDIUMTEXT，一次性塞几千条大文本
     * 容易顶到 max_allowed_packet，分批更稳。
     */
    public void insertChunks(long repoId, List<CollectedChunk> chunks,
                             Map<String, Long> fileIds, Map<String, Long> symbolIds) {
        if (chunks.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                        INSERT INTO `chunk`
                          (repo_id, file_id, symbol_id, kind, start_line, end_line,
                           content_hash, content, token_estimate)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                chunks, 200, (ps, chunk) -> {
                    Long fileId = fileIds.get(chunk.filePath());
                    if (fileId == null) {
                        throw new IllegalStateException("检索单元找不到所属文件：" + chunk.filePath());
                    }
                    ps.setLong(1, repoId);
                    ps.setLong(2, fileId);
                    // FILE_HEADER 块不属于任何单个符号，这里为 NULL 是正常的
                    Long symbolId = chunk.symbolKey() == null ? null : symbolIds.get(chunk.symbolKey());
                    if (symbolId == null) {
                        ps.setNull(3, java.sql.Types.BIGINT);
                    } else {
                        ps.setLong(3, symbolId);
                    }
                    ps.setString(4, chunk.kind());
                    ps.setInt(5, chunk.startLine());
                    ps.setInt(6, chunk.endLine());
                    ps.setString(7, chunk.contentHash());
                    ps.setString(8, chunk.content());
                    ps.setInt(9, chunk.tokenEstimate());
                });
    }

    public void finishRepo(long repoId, int fileCount, int parsedOkCount, int totalLoc,
                           int symbolCount, int callEdgeCount, int callResolvedCount) {
        jdbc.update("""
                UPDATE `repo`
                   SET status = 'READY', file_count = ?, parsed_ok_count = ?, total_loc = ?,
                       symbol_count = ?, call_edge_count = ?, call_resolved_count = ?,
                       indexed_at = ?
                 WHERE id = ?
                """, fileCount, parsedOkCount, totalLoc, symbolCount, callEdgeCount, callResolvedCount,
                Timestamp.valueOf(LocalDateTime.now()), repoId);
    }

    /** 删仓库：子表（源文件/符号/调用边/类型关系/chunk/题目/评估记录）都是 ON DELETE CASCADE，会跟着走。 */
    public boolean deleteRepo(long repoId) {
        return jdbc.update("DELETE FROM `repo` WHERE id = ?", repoId) > 0;
    }

    public void failRepo(long repoId, String errorMessage) {
        jdbc.update("UPDATE `repo` SET status = 'FAILED', error_msg = ?, indexed_at = ? WHERE id = ?",
                errorMessage, Timestamp.valueOf(LocalDateTime.now()), repoId);
    }

    /** 调用边的未解析原因分布 —— 「哪里没解析出来、为什么」本身就是要如实报告的结论。 */
    public Map<String, Integer> unresolvedReasonCounts(long repoId) {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        jdbc.query("""
                        SELECT COALESCE(reason, 'UNKNOWN') AS reason, COUNT(*) AS c
                          FROM `call_edge`
                         WHERE repo_id = ? AND resolved = 0
                         GROUP BY reason
                         ORDER BY c DESC
                        """,
                rs -> {
                    counts.put(rs.getString("reason"), rs.getInt("c"));
                }, repoId);
        return counts;
    }

    public Map<String, Integer> unresolvedRelationCounts(long repoId) {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        jdbc.query("SELECT external, COUNT(*) AS c FROM `type_relation` WHERE repo_id = ? GROUP BY external",
                rs -> {
                    counts.put(rs.getBoolean("external") ? "EXTERNAL" : "RESOLVED", rs.getInt("c"));
                }, repoId);
        return counts;
    }
}
