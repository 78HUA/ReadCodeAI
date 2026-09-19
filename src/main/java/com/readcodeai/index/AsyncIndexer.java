package com.readcodeai.index;

import com.readcodeai.config.ReadCodeAiProperties;
import com.readcodeai.index.model.IndexJob;
import com.readcodeai.index.store.IndexJobRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 异步索引：**接单立刻返回，干活在后台，进度随时可查**。
 *
 * <h3>为什么必须异步</h3>
 * 索引是分钟级的长任务（实测 2.3 万行 16 秒，十万行就是分钟级）。原来它是同步的：
 * 一次 {@code POST /api/repos} 把 HTTP 请求挂十几秒到几分钟 —— 前端只能干等，
 * 网关一超时就断，客户端还以为失败了。
 *
 * <h3>为什么先用进程内队列、不直接上 MQ</h3>
 * 这个场景真正需要的是"不阻塞 + 有进度"，而这两件事**进程内队列就够**。
 * MQ 额外买到的是"重启不丢 / 自动重试 / 多实例横向扩" —— 现在用不上，
 * 硬加只会让面试里的"为什么"答不顺（判据见 docs/design-outline.md 的选型表）。
 *
 * <p><b>它的代价必须说清</b>：进程重启 → 排队与运行中的任务**丢掉**。
 * 所以这里有 {@link #reconcileUnfinishedJobs()}：启动时把上次留下的任务如实标成失败，
 * 而不是让仓库永远卡在 INDEXING 骗人。这条代价就是将来换 MQ 的理由。
 *
 * <h3>并发度为什么是 1</h3>
 * 索引是 CPU + 磁盘密集型的，两个仓库同时跑只会互相拖慢，还抢数据库连接。
 * 队列容量 8：满了就明确拒绝（"已有索引任务在跑"），而不是无限堆积。
 */
@Service
public class AsyncIndexer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AsyncIndexer.class);

    /** 进度写库的节流间隔：每个文件都写一次，对几千文件的仓库来说是纯浪费。 */
    private static final long PROGRESS_THROTTLE_MS = 300;

    private static final String RESTARTED_MESSAGE =
            "应用在索引过程中重启：索引任务**不持久化**（进程内队列的代价），请重新提交";

    private final ProjectIndexer indexer;
    private final IndexJobRepository jobs;
    private final RepoFetcher repoFetcher;
    private final ReadCodeAiProperties properties;

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8),
            runnable -> {
                Thread thread = new Thread(runnable, "index-worker");
                thread.setDaemon(true);
                return thread;
            });

    public AsyncIndexer(ProjectIndexer indexer, IndexJobRepository jobs, RepoFetcher repoFetcher,
                        ReadCodeAiProperties properties) {
        this.indexer = indexer;
        this.jobs = jobs;
        this.repoFetcher = repoFetcher;
        this.properties = properties;
    }

    /**
     * 启动时对账：上次进程留下的 QUEUED/RUNNING 任务与卡在 INDEXING 的仓库，一并如实标成失败。
     *
     * <p>这里**不自动重跑**：重跑需要知道"任务从哪来、跑到哪一步了"，那是队列该干的事。
     * 现在老老实实告诉使用者"请重新提交"，比假装什么都没发生强。
     */
    @Override
    public void run(ApplicationArguments args) {
        reconcileUnfinishedJobs();
    }

    @PostConstruct
    void logBootstrap() {
        log.info("异步索引已启用：单 worker（索引是 CPU/磁盘密集型，并发跑只会互相拖慢）· 队列容量 8");
    }

    public int reconcileUnfinishedJobs() {
        int staleJobs = jobs.failUnfinishedJobs(RESTARTED_MESSAGE);
        int staleRepos = jobs.failStaleRepos(RESTARTED_MESSAGE);
        if (staleJobs > 0 || staleRepos > 0) {
            log.warn("启动对账：{} 个未完成的索引任务、{} 个卡在 INDEXING 的仓库已标为失败（进程内队列不持久化）",
                    staleJobs, staleRepos);
        }
        return staleJobs;
    }

    /** 提交一个**本地路径**索引任务：路径已知，所以能立刻建仓库行并把 repoId 交给调用方。 */
    public IndexJob submitLocal(Path path) {
        Path root = path.toAbsolutePath().normalize();
        // 建行放在提交线程里做：这样接口返回时 repoId 就是有效的，前端可以立刻开始轮询
        long repoId = indexer.createPendingRepo(root, null);
        long jobId = jobs.create("LOCAL", root.toString());
        jobs.start(jobId, repoId, "QUEUED", "排队中");
        submit(jobId, () -> indexer.indexInto(repoId, root, name(root), null, listenerFor(jobId)));
        return jobs.find(jobId).orElseThrow();
    }

    /** 提交一个 **GitHub 链接**索引任务：仓库行要等拉取完才知道根路径。 */
    public IndexJob submitRemote(String gitUrl) {
        long jobId = jobs.create("GIT", gitUrl);
        jobs.start(jobId, null, "QUEUED", "排队中");
        submit(jobId, () -> {
            reporter(jobId).onProgress("FETCHING", 0, 0, "拉取 GitHub 源码包");
            RepoFetcher.Fetched fetched = fetch(gitUrl);
            long repoId = indexer.createPendingRepo(fetched.root(), fetched.commitHash());
            jobs.start(jobId, repoId, "SCANNING", "已拉取，开始索引");
            return indexer.indexInto(repoId, fetched.root(), name(fetched.root()), fetched.commitHash(),
                    listenerFor(jobId));
        });
        return jobs.find(jobId).orElseThrow();
    }

    /** 提交一个**压缩包**索引任务：压缩包先落到工作区（快），解压与索引都在后台做。 */
    public IndexJob submitArchive(Path archive, String originalFilename) {
        long jobId = jobs.create("ARCHIVE", originalFilename == null ? archive.getFileName().toString() : originalFilename);
        jobs.start(jobId, null, "QUEUED", "排队中");
        submit(jobId, () -> {
            reporter(jobId).onProgress("EXTRACTING", 0, 0, "解压压缩包");
            Path root = extractArchive(archive, originalFilename);
            long repoId = indexer.createPendingRepo(root, null);
            jobs.start(jobId, repoId, "SCANNING", "已解压，开始索引");
            return indexer.indexInto(repoId, root, name(root), null, listenerFor(jobId));
        });
        return jobs.find(jobId).orElseThrow();
    }

    /** 队列里还有多少任务（界面与测试都用得上）。 */
    public int queued() {
        return executor.getQueue().size();
    }

    /** 正在跑的任务数（0 或 1）。 */
    public int running() {
        return executor.getActiveCount();
    }

    private void submit(long jobId, Supplier<IndexSummary> work) {
        try {
            executor.execute(() -> {
                try {
                    IndexSummary summary = work.get();
                    jobs.finish(jobId, "READY", "DONE", summary.fileCount() + " 个文件 · "
                            + summary.symbolCount() + " 个符号 · " + summary.totalMillis() + " ms");
                } catch (RuntimeException | LinkageError e) {
                    // 任务失败要能看到原因：这就是"哪些仓库索引不了"的答案
                    log.warn("索引任务 {} 失败：{}", jobId, e.toString());
                    jobs.finish(jobId, "FAILED", "FAILED", e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // 队列满 = 明确的"忙"，而不是无限堆积（堆积只会让所有任务都变慢）
            jobs.finish(jobId, "FAILED", "FAILED", "索引队列已满（最多 8 个排队），请稍后再提交");
            throw new IllegalStateException("索引队列已满，请稍后再提交（当前排队 " + queued() + " 个）");
        }
    }

    private RepoFetcher.Fetched fetch(String gitUrl) {
        return repoFetcher.fetch(gitUrl, Path.of(properties.getIndex().getWorkspace()));
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

    /**
     * 任务进度监听器：**写库前节流**。
     *
     * <p>解析阶段每个文件都会回调一次，几千个文件就有几千次写库 —— 那是给数据库白加负载。
     * 这里按"间隔 300ms 或首尾各一次"写，界面上的观感不变，写入量掉两个数量级。
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

    /** 给"上报前还不知道任务 id"的阶段用（拉取/解压）。 */
    private ProgressListener reporter(long jobId) {
        return (stage, done, total, message) -> jobs.progress(jobId, stage, done, total, shortMessage(message));
    }

    static String shortMessage(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 240 ? message : message.substring(0, 240);
    }

    private static String name(Path root) {
        return root.getFileName() == null ? root.toString() : root.getFileName().toString();
    }
}
