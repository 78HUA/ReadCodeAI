package com.readcodeai.index.store;

import com.readcodeai.index.model.CollectedCall;
import com.readcodeai.index.model.CollectedSymbol;
import com.readcodeai.index.model.CollectedTypeRelation;
import com.readcodeai.index.model.FileOutcome;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
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
 * <p>符号要拿到自增 id 才能建调用边，所以符号是逐条插入（本地 MySQL 下千级插入耗时可忽略）；
 * 调用边数量大且不需要回填 id，用批量插入。
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

    /** @return 相对路径 -> source_file.id */
    public Map<String, Long> insertSourceFiles(long repoId, List<FileOutcome> files) {
        Map<String, Long> ids = new HashMap<>(files.size() * 2);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        for (FileOutcome file : files) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO `source_file`
                          (repo_id, path, content_hash, loc, parsed_ok, parse_error, indexed_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, repoId);
                ps.setString(2, file.relativePath());
                ps.setString(3, file.contentHash());
                ps.setInt(4, file.loc());
                ps.setBoolean(5, file.parsedOk());
                ps.setString(6, file.errorMessage());
                ps.setTimestamp(7, now);
                return ps;
            }, keyHolder);
            Number key = keyHolder.getKey();
            if (key != null) {
                ids.put(file.relativePath(), key.longValue());
            }
        }
        return ids;
    }

    /**
     * 插入符号并回填父类型 id。
     *
     * <p>依赖 analyze 的输出顺序：父类型一定排在它的成员之前，所以「已在表里的键」就是父 id。
     *
     * @return 符号检索键 -> symbol.id
     */
    public Map<String, Long> insertSymbols(long repoId, List<CollectedSymbol> symbols,
                                           Map<String, Long> fileIds) {
        Map<String, Long> ids = new HashMap<>(symbols.size() * 2);
        for (CollectedSymbol symbol : symbols) {
            Long fileId = fileIds.get(symbol.filePath());
            if (fileId == null) {
                continue;
            }
            Long parentId = symbol.parentQualifiedName() == null ? null : ids.get(symbol.parentQualifiedName());
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(INSERT_SYMBOL, Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, repoId);
                ps.setLong(2, fileId);
                ps.setString(3, symbol.kind());
                ps.setString(4, symbol.name());
                ps.setString(5, symbol.qualifiedName());
                ps.setString(6, symbol.signature());
                if (parentId == null) {
                    ps.setNull(7, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(7, parentId);
                }
                ps.setInt(8, symbol.startLine());
                ps.setInt(9, symbol.endLine());
                ps.setString(10, symbol.modifiers());
                ps.setString(11, symbol.returnType());
                ps.setString(12, symbol.javadoc());
                return ps;
            }, keyHolder);
            Number key = keyHolder.getKey();
            if (key != null) {
                ids.put(symbol.qualifiedName(), key.longValue());
            }
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

    public void insertRelations(long repoId, List<CollectedTypeRelation> relations, Map<String, Long> symbolIds) {
        jdbc.batchUpdate("""
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
