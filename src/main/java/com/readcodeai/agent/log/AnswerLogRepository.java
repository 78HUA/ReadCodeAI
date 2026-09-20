package com.readcodeai.agent.log;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code answer_log} 的落库与聚合：**只做读写，不做判断**。
 *
 * <p>该不该记、算多少钱、截断到多长，都由 {@link AnswerLogService} 决定 ——
 * 这样"记账的规则"只有一处，而这一层薄到可以直接对着 SQL 核对。
 */
@Repository
public class AnswerLogRepository {

    private final JdbcTemplate jdbc;

    public AnswerLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AnswerLogEntry e) {
        jdbc.update("""
                INSERT INTO `answer_log`
                    (repo_id, question_id, source, question, mode, answered_by, route_json, hops,
                     prompt_tokens, completion_tokens, support_prompt_tokens, support_completion_tokens,
                     cost, latency_ms, evidence_verified, evidence_rejected, support_status,
                     refused, refusal_reason, cache_hit, answer_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                e.repoId(), e.questionId(), e.source(), e.question(), e.mode(), e.answeredBy(), e.routeJson(),
                e.hops(), e.promptTokens(), e.completionTokens(),
                e.supportPromptTokens(), e.supportCompletionTokens(),
                BigDecimal.valueOf(e.cost()), e.latencyMs(), e.evidenceVerified(), e.evidenceRejected(),
                e.supportStatus(), e.refused(), e.refusalReason(), e.cacheHit(), e.answerJson(),
                Timestamp.valueOf(LocalDateTime.now()));
    }

    /**
     * 累计统计。{@code repoId} 为 null 时统计**全部仓库**。
     *
     * <p>两条 SQL 而不是一条：主统计只看**用户提问**（评估跑题单独计数）——
     * 用条件聚合把两种口径塞进一条 SQL 里，改起来容易漏掉一个 CASE，读起来也难核对。
     *
     * <p>token 与金额都按 {@code cache_hit} 分开算：命中缓存的那次没有真的再花一遍，
     * 把它算进"实际花费"会让累计值随重复提问虚涨（见 {@link AnswerLogStats} 的口径说明）。
     */
    public AnswerLogStats stats(Long repoId) {
        AnswerLogStats totals = jdbc.query("""
                SELECT COUNT(*)                                                                     AS user_questions,
                       COALESCE(SUM(refused), 0)                                                    AS refusals,
                       COALESCE(SUM(cache_hit), 0)                                                  AS cache_hits,
                       COALESCE(SUM(CASE WHEN cache_hit = 0 THEN prompt_tokens ELSE 0 END), 0)      AS prompt_tokens,
                       COALESCE(SUM(CASE WHEN cache_hit = 0 THEN completion_tokens ELSE 0 END), 0)  AS completion_tokens,
                       COALESCE(SUM(CASE WHEN cache_hit = 0
                                         THEN support_prompt_tokens + support_completion_tokens
                                         ELSE 0 END), 0)                                            AS support_tokens,
                       COALESCE(SUM(CASE WHEN cache_hit = 0 THEN cost ELSE 0 END), 0)               AS cost,
                       COALESCE(SUM(CASE WHEN cache_hit = 1
                                         THEN prompt_tokens + completion_tokens
                                              + support_prompt_tokens + support_completion_tokens
                                         ELSE 0 END), 0)                                            AS saved_tokens,
                       COALESCE(SUM(CASE WHEN cache_hit = 1 THEN cost ELSE 0 END), 0)               AS saved_cost,
                       COALESCE(ROUND(AVG(latency_ms)), 0)                                          AS avg_latency,
                       COALESCE(MAX(latency_ms), 0)                                                 AS max_latency,
                       COALESCE(SUM(evidence_verified), 0)                                          AS evidence_verified,
                       COALESCE(SUM(evidence_rejected), 0)                                          AS evidence_rejected
                  FROM `answer_log`
                 WHERE source = 'USER' AND (? IS NULL OR repo_id = ?)
                """, rs -> {
            if (!rs.next()) {
                return AnswerLogStats.empty();
            }
            return new AnswerLogStats(
                    rs.getLong("user_questions"), 0, rs.getLong("refusals"), rs.getLong("cache_hits"),
                    rs.getLong("prompt_tokens"), rs.getLong("completion_tokens"),
                    rs.getLong("support_tokens"), rs.getDouble("cost"),
                    rs.getLong("saved_tokens"), rs.getDouble("saved_cost"),
                    rs.getLong("avg_latency"), rs.getLong("max_latency"),
                    rs.getLong("evidence_verified"), rs.getLong("evidence_rejected"),
                    List.of());
        }, repoId, repoId);

        long evalQuestions = jdbc.query("""
                SELECT COUNT(*) FROM `answer_log`
                 WHERE source = 'EVAL' AND (? IS NULL OR repo_id = ?)
                """, rs -> rs.next() ? rs.getLong(1) : 0L, repoId, repoId);

        List<AnswerLogStats.ModeRow> byMode = jdbc.query("""
                        SELECT mode,
                               COUNT(*)                          AS c,
                               COALESCE(SUM(refused), 0)         AS r,
                               COALESCE(ROUND(AVG(latency_ms)), 0) AS l
                          FROM `answer_log`
                         WHERE source = 'USER' AND (? IS NULL OR repo_id = ?)
                         GROUP BY mode
                         ORDER BY c DESC
                        """,
                (rs, rowNum) -> new AnswerLogStats.ModeRow(rs.getString("mode"),
                        rs.getLong("c"), rs.getLong("r"), rs.getLong("l")),
                repoId, repoId);

        return new AnswerLogStats(totals.userQuestions(), evalQuestions, totals.refusals(), totals.cacheHits(),
                totals.promptTokens(), totals.completionTokens(), totals.supportTokens(), totals.cost(),
                totals.savedTokens(), totals.savedCost(), totals.avgLatencyMs(), totals.maxLatencyMs(),
                totals.evidenceVerified(), totals.evidenceRejected(), byMode);
    }
}
