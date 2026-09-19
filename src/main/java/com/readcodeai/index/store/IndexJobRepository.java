package com.readcodeai.index.store;

import com.readcodeai.index.model.IndexJob;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 索引任务的读写。**它是"异步"这件事的唯一状态源**：接单、报进度、结单、以及启动时对账。
 *
 * <p>进度写入非常频繁（每个文件一次），所以 {@link #progress} 用单条 UPDATE 且由调用方节流；
 * 这里不做任何额外查询 —— 让长任务的写入开销可以忽略。
 */
@Repository
public class IndexJobRepository {

    private final JdbcTemplate jdbc;

    public IndexJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 接单：建一条 QUEUED 的任务，返回任务 id。
     *
     * <p><b>用 KeyHolder 而不是 {@code SELECT LAST_INSERT_ID()}</b>：连接池下两次 JdbcTemplate 调用
     * 很可能是**两条不同的连接**，而 {@code LAST_INSERT_ID()} 是连接级的 ——
     * 那样取到的 id 会是别的连接的（或者 0）。这种 bug 在低并发时看不出来，
     * 一旦有人同时提交两个任务就会张冠李戴。
     */
    public long create(String kind, String source) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO `index_job` (kind, source, status, stage, message, created_at, updated_at)
                    VALUES (?, ?, 'QUEUED', 'QUEUED', ?, NOW(), NOW())
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, kind);
            statement.setString(2, source);
            statement.setString(3, "排队中");
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("建索引任务失败：没有拿到自增 id");
        }
        return key.longValue();
    }

    /** 开始执行：状态转 RUNNING，并把仓库行（如果已经有）挂上去。 */
    public void start(long jobId, Long repoId, String stage, String message) {
        jdbc.update("""
                UPDATE `index_job`
                   SET status = 'RUNNING', repo_id = ?, stage = ?, message = ?, updated_at = NOW()
                 WHERE id = ?
                """, repoId, stage, message, jobId);
    }

    /** 报进度（调用方负责节流）。 */
    public void progress(long jobId, String stage, int done, int total, String message) {
        jdbc.update("""
                UPDATE `index_job`
                   SET stage = ?, done = ?, total = ?, message = ?, updated_at = NOW()
                 WHERE id = ?
                """, stage, done, total, message, jobId);
    }

    /** 结单。 */
    public void finish(long jobId, String status, String stage, String message) {
        jdbc.update("""
                UPDATE `index_job`
                   SET status = ?, stage = ?, message = ?, updated_at = NOW()
                 WHERE id = ?
                """, status, stage, message, jobId);
    }

    public Optional<IndexJob> find(long jobId) {
        List<IndexJob> found = jdbc.query(SELECT + " WHERE id = ?", this::toJob, jobId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /** 某个仓库最近一次任务 —— 界面看进度用它。 */
    public Optional<IndexJob> latestForRepo(long repoId) {
        List<IndexJob> found = jdbc.query(SELECT + " WHERE repo_id = ? ORDER BY id DESC LIMIT 1",
                this::toJob, repoId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /**
     * 启动时对账：把上次进程留下的 QUEUED/RUNNING 任务标成 FAILED。
     *
     * <p><b>这是"没有消息队列"的真实代价</b>：任务不持久化，进程一重启就没了。
     * 与其让它永远卡在"运行中"骗人，不如如实标成失败并说明原因 ——
     * 也顺带把"什么时候该上 MQ"这个问题摆到台面上（要自动接着跑，就得有队列）。
     *
     * @return 标掉的任务数
     */
    public int failUnfinishedJobs(String message) {
        return jdbc.update("""
                UPDATE `index_job`
                   SET status = 'FAILED', stage = 'FAILED', message = ?, updated_at = NOW()
                 WHERE status IN ('QUEUED', 'RUNNING')
                """, message);
    }

    /** 仓库行卡在 INDEXING 的一并对账（任务表与仓库表必须一致，否则界面自相矛盾）。 */
    public int failStaleRepos(String message) {
        return jdbc.update("""
                UPDATE `repo`
                   SET status = 'FAILED', error_msg = ?
                 WHERE status = 'INDEXING'
                """, message);
    }

    private static final String SELECT = """
            SELECT id, repo_id, kind, source, status, stage, done, total, message, created_at, updated_at
              FROM `index_job`
            """;

    private IndexJob toJob(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        long repoId = rs.getLong("repo_id");
        return new IndexJob(rs.getLong("id"), rs.wasNull() ? null : repoId,
                rs.getString("kind"), rs.getString("source"), rs.getString("status"),
                rs.getString("stage"), rs.getInt("done"), rs.getInt("total"),
                rs.getString("message"),
                toLocal(rs.getTimestamp("created_at")), toLocal(rs.getTimestamp("updated_at")));
    }

    private static LocalDateTime toLocal(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
