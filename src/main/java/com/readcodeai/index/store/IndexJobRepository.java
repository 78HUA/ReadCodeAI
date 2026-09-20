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

    /**
     * MQ 模式下的对账：把上次进程留下的 RUNNING 任务改回 QUEUED —— **它们会随着消息重投自动接着跑**。
     *
     * <p>与 {@link #failUnfinishedJobs} 的差别就是"有没有队列"这件事本身：
     * 进程内队列丢了就是丢了（只能标失败、让人重交），MQ 的消息没被 ack，重启后会被重新投递。
     * 两条路径都留着，对照实验才做得出来。
     */
    public int requeueRunningJobs(String message) {
        return jdbc.update("""
                UPDATE `index_job`
                   SET status = 'QUEUED', stage = 'QUEUED', message = ?, updated_at = NOW()
                 WHERE status = 'RUNNING'
                """, message);
    }

    /** 状态计数（队列实现用它报"正在跑几个"，跨实例也准）。 */
    public Integer countByStatus(String status) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM `index_job` WHERE status = ?", Integer.class, status);
    }

    /**
     * 排队超过 N 分钟还没被执行的 QUEUED 任务 —— 这是"消息可能丢了"的信号。
     *
     * <p>只**报告**不自动改状态：在 MQ 模式下别的地方可能正拿着这条消息，
     * 自动标失败会把它变成"跑着跑着变失败"的鬼故事。巡检日志 + 人判断，比自作聪明安全。
     */
    public List<Long> findStaleQueued(int olderThanMinutes) {
        return jdbc.queryForList("""
                SELECT id FROM `index_job`
                 WHERE status = 'QUEUED' AND updated_at < NOW() - INTERVAL ? MINUTE
                """, Long.class, olderThanMinutes);
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
        // ⚠️ `wasNull()` 只反映**最近一次**读取的列 —— 必须紧接着 getLong 判断。
        // 写成 `new IndexJob(rs.getLong("id"), rs.wasNull() ? null : repoId, ...)` 是错的：
        // 参数从左到右求值，先读了 id，wasNull 就在回答"id 是不是 null"（永远 false），
        // 于是 **repo_id = NULL 被读成 0**。旧代码不分支所以看不出；一旦有人写
        // `job.repoId() == null ? 建仓库行 : 用它`（队列化之后就是这么写的），就会拿 0 去写外键、
        // 报 "Cannot add or update a child row"（这个 bug 是队列化测试逼出来的）。
        long id = rs.getLong("id");
        long repoIdValue = rs.getLong("repo_id");
        Long repoId = rs.wasNull() ? null : repoIdValue;
        return new IndexJob(id, repoId,
                rs.getString("kind"), rs.getString("source"), rs.getString("status"),
                rs.getString("stage"), rs.getInt("done"), rs.getInt("total"),
                rs.getString("message"),
                toLocal(rs.getTimestamp("created_at")), toLocal(rs.getTimestamp("updated_at")));
    }

    private static LocalDateTime toLocal(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
