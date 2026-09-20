package com.readcodeai.index.queue;

import com.readcodeai.index.IndexSummary;
import com.readcodeai.index.ProjectIndexer;
import com.readcodeai.index.ProgressListener;
import com.readcodeai.index.RepoFetcher;
import com.readcodeai.index.ZipExtractor;
import com.readcodeai.index.model.IndexJob;
import com.readcodeai.index.store.IndexJobRepository;
import com.readcodeai.config.ReadCodeAiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 执行一个 {@link IndexTask}：**幂等检查 → 按类型复原步骤 → 跑索引 → 写终态**。
 *
 * <h3>为什么单独一个类、且不带任何 MQ 注解</h3>
 * 这里的逻辑是"任务怎么执行"，与"任务怎么送过来"无关。分开之后：
 * 它既可以被进程内队列直接调用，也可以被 MQ 消费者调用，还能**在离线测试里直接驱动** ——
 * 幂等与状态机是这个模块最容易悄悄错的地方，必须能不起 broker 就测。
 *
 * <h3>幂等为什么必须做</h3>
 * 消息队列的投递语义是 **at-least-once**：消费完、还没 ack 就崩，消息会被重投。
 * 所以"同一个任务被执行两次"是**必然会发生**的事，不是异常。这里的做法是：
 * 开工前查状态，已经是终态（READY/FAILED）就直接跳过 —— 一行判断挡住整次重复索引。
 * （索引本身"删了再插"的覆盖语义也是幂等的，两层叠起来足够。）
 */
public class IndexTaskRunner {

    private static final Logger log = LoggerFactory.getLogger(IndexTaskRunner.class);

    /** 进度写库的节流间隔：每个文件都写一次，对几千文件的仓库来说是纯浪费。 */
    private static final long PROGRESS_THROTTLE_MS = 300;

    private final ProjectIndexer indexer;
    private final IndexJobRepository jobs;
    private final RepoFetcher repoFetcher;
    private final ReadCodeAiProperties properties;
    private final RepoLock repoLock;

    public IndexTaskRunner(ProjectIndexer indexer, IndexJobRepository jobs, RepoFetcher repoFetcher,
                           ReadCodeAiProperties properties, RepoLock repoLock) {
        this.indexer = indexer;
        this.jobs = jobs;
        this.repoFetcher = repoFetcher;
        this.properties = properties;
        this.repoLock = repoLock;
    }

    /**
     * 跑一个任务。**成功返回、失败抛异常**（抛出去是给 MQ 看的：它据此把消息送进死信队列）。
     *
     * <p>失败时任务行已经写成 FAILED 再抛 —— 使用者看表就知道为什么失败，不用去翻 broker。
     */
    public void run(IndexTask task) {
        IndexJob job = jobs.find(task.jobId()).orElse(null);
        if (job == null) {
            // 消息指向一个不存在的任务：这种消息只能进死信队列，重试多少次都不会变好
            throw new IllegalStateException("任务不存在：" + task.describe());
        }
        if (isTerminal(job.status())) {
            log.info("任务幂等跳过（已经是 {}）：{}", job.status(), task.describe());
            return;
        }

        try {
            IndexSummary summary = switch (task.type()) {
                case LOCAL -> indexWithLock(task, job, Path.of(task.payload()), null);
                case GIT -> runGit(task, job);
                case ARCHIVE -> runArchive(task, job);
            };
            jobs.finish(task.jobId(), "READY", "DONE", shortMessage(summary.fileCount() + " 个文件 · "
                    + summary.symbolCount() + " 个符号 · " + summary.totalMillis() + " ms"));
            log.info("索引任务 {} 完成：{} 个文件 · {} 个符号 · {} ms",
                    task.jobId(), summary.fileCount(), summary.symbolCount(), summary.totalMillis());
        } catch (RuntimeException | LinkageError e) {
            // 先写终态再抛：表里能看到原因，MQ 那边也能把消息送去死信（不无限重投）。
            // 消息必须截断：**数据库异常的 message 里带着整条 SQL**，超长会把"标记失败"这一步也写挂
            // —— 结果是任务永远卡在 RUNNING（这个坑是测试逼出来的，见 verification-log）
            log.warn("索引任务 {} 失败：{}", task.jobId(), e.toString());
            jobs.finish(task.jobId(), "FAILED", "FAILED",
                    shortMessage(e.getClass().getSimpleName() + ": " + e.getMessage()));
            throw e instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("索引失败：" + e.getMessage(), e);
        }
    }

    private IndexSummary runGit(IndexTask task, IndexJob job) {
        reporter(task.jobId()).onProgress("FETCHING", 0, 0, "拉取 GitHub 源码包");
        RepoFetcher.Fetched fetched;
        // 拉取阶段按 **URL** 互斥：同一个链接的两条任务会往同一个工作区目录里写，互相覆盖
        try (RepoLock.Handle lock = requireLock("git:" + task.payload(), task)) {
            fetched = repoFetcher.fetch(task.payload(), Path.of(properties.getIndex().getWorkspace()));
        }
        return indexWithLock(task, job, fetched.root(), fetched.commitHash());
    }

    private IndexSummary runArchive(IndexTask task, IndexJob job) {
        reporter(task.jobId()).onProgress("EXTRACTING", 0, 0, "解压压缩包");
        // 解压目标目录是按压缩包名固定的：解压阶段也按它互斥（否则两个同名包会互相清目录）
        Path root;
        try (RepoLock.Handle lock = requireLock("archive:" + job.source(), task)) {
            root = extractArchive(Path.of(task.payload()), job.source());
        }
        return indexWithLock(task, job, root, null);
    }

    /**
     * **索引阶段**的统一入口：先拿"按仓库路径"的锁，再建仓库行 + 跑索引。
     *
     * <p>为什么锁必须包住"建仓库行"这一步：它是 {@code DELETE + INSERT}（同一路径重复索引 = 覆盖），
     * 两个任务同时做会互相删掉对方刚写的行 —— 这正是这把锁要防的事。
     */
    private IndexSummary indexWithLock(IndexTask task, IndexJob job, Path root, String commitHash) {
        try (RepoLock.Handle lock = requireLock(root.toAbsolutePath().normalize().toString(), task)) {
            if (lock.waitedMillis() > 0) {
                job = jobs.find(task.jobId()).orElse(job);   // 等锁期间别人可能已更新过这行
            }
            long repoId = job.repoId() == null ? pendingRepo(task, root, commitHash) : job.repoId();
            jobs.start(task.jobId(), repoId, "SCANNING", "开始索引");
            return indexer.indexInto(repoId, root, name(root), commitHash, listenerFor(task.jobId()));
        }
    }

    /** 拿锁；等不到（超时）就抛出可诊断的失败 —— 任务表里会写清"是和谁堵上了"。 */
    private RepoLock.Handle requireLock(String resource, IndexTask task) {
        return repoLock.acquire(resource).orElseThrow(() -> new IllegalStateException(
                "等待仓库锁超时：" + resource + " 正被另一个索引任务占用（任务 " + task.jobId() + "）；请稍后重试"));
    }

    /** 仓库行在提交时建过一次（本机路径）就复用；没有就补建 —— 消费端可能跑在另一个进程里。 */
    private long pendingRepo(IndexTask task, Path root, String commitHash) {
        long repoId = indexer.createPendingRepo(root, commitHash);
        jobs.start(task.jobId(), repoId, "QUEUED", "已认领任务，准备索引");
        return repoId;
    }

    private static boolean isTerminal(String status) {
        return "READY".equals(status) || "FAILED".equals(status) || "DONE".equals(status);
    }

    /**
     * 任务进度监听器：**写库前节流**。解析阶段每个文件都会回调一次，几千个文件就是几千次写库。
     * 按"间隔 300ms 或首尾各一次"写，界面观感不变，写入量掉两个数量级。
     */
    private ProgressListener listenerFor(long jobId) {
        AtomicLong lastWrite = new AtomicLong(0);
        return (stage, done, total, message) -> {
            long now = System.currentTimeMillis();
            boolean isStageChange = done <= 1 || (total > 0 && done >= total);
            if (isStageChange || now - lastWrite.get() >= PROGRESS_THROTTLE_MS) {
                lastWrite.set(now);
                jobs.progress(jobId, stage, done, total, shortMessage(message));
            }
        };
    }

    /** 给"上报前还不知道任务 id"的阶段用（拉取/解压）。与上面不同：这两步只有两三次回调，不需要节流。 */
    private ProgressListener reporter(long jobId) {
        return (stage, done, total, message) -> jobs.progress(jobId, stage, done, total, shortMessage(message));
    }

    Path extractArchive(Path archive, String originalFilename) {
        Path workspace = Path.of(properties.getIndex().getWorkspace()).resolve("uploads");
        Path root = workspace.resolve(ProjectIndexer.safeArchiveName(originalFilename));
        try {
            ZipExtractor.clearDirectory(root);
            ZipExtractor.extract(archive, root, ZipExtractor.hasSingleTopLevelDirectory(archive));
            return root;
        } catch (IOException e) {
            throw new IllegalStateException("解压失败：" + e.getMessage(), e);
        }
    }

    public static String shortMessage(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 240 ? message : message.substring(0, 240);
    }

    private static String name(Path root) {
        return root.getFileName() == null ? root.toString() : root.getFileName().toString();
    }
}
