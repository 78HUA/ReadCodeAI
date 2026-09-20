package com.readcodeai.index;

import com.readcodeai.config.ReadCodeAiProperties;
import com.readcodeai.index.model.IndexJob;
import com.readcodeai.index.queue.IndexTask;
import com.readcodeai.index.queue.IndexTaskQueue;
import static com.readcodeai.index.queue.IndexTaskRunner.shortMessage;
import com.readcodeai.index.store.IndexJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

/**
 * 异步索引的**提交侧**：接单、建任务、把任务交给队列，然后立刻返回。
 *
 * <h3>职责边界（这次改造划清的）</h3>
 * <ul>
 *   <li>这里只管"**接单与投递**"：建 {@code index_job} 行（状态源）、建仓库行、把
 *       {@link IndexTask} 交给 {@link IndexTaskQueue}。</li>
 *   <li>"**怎么执行**"在 {@code IndexTaskRunner}（按类型复原步骤 + 幂等 + 写终态），
 *       两种队列共用同一份 —— 所以"进程内 vs MQ"的对照实验里，差异只剩投递方式。</li>
 * </ul>
 *
 * <h3>两种模式的对账语义（差别就是"有没有队列"本身）</h3>
 * <ul>
 *   <li><b>in-process</b>：重启 → 排队与运行中的任务**丢了**，如实标成失败并提示重新提交。</li>
 *   <li><b>rabbit</b>：重启 → 没被 ack 的消息会被**重新投递**，所以把 RUNNING 改回 QUEUED
 *       （等重投），**不再标失败**；另外把"排队很久还没人执行"的任务报出来（消息可能真的丢了）。</li>
 * </ul>
 */
@Service
public class AsyncIndexer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AsyncIndexer.class);

    private static final String RESTARTED_MESSAGE =
            "应用在索引过程中重启：索引任务**不持久化**（进程内队列的代价），请重新提交";

    private static final String REQUEUE_MESSAGE = "应用重启，等待消息队列重新投递";

    /** 巡检阈值：排队超过这么久还没被执行，就值得怀疑消息丢了。 */
    private static final int STALE_QUEUED_MINUTES = 10;

    private final ProjectIndexer indexer;
    private final IndexJobRepository jobs;
    private final IndexTaskQueue queue;
    private final ReadCodeAiProperties properties;

    public AsyncIndexer(ProjectIndexer indexer, IndexJobRepository jobs, IndexTaskQueue queue,
                        ReadCodeAiProperties properties) {
        this.indexer = indexer;
        this.jobs = jobs;
        this.queue = queue;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("索引任务队列：{}", queue.describe());
        reconcileUnfinishedJobs();
    }

    /**
     * 启动对账：**按队列模式分别处理**（见类注释）。
     *
     * @return 被处理（标失败或被改回排队）的任务数
     */
    public int reconcileUnfinishedJobs() {
        if (properties.getQueue().getMode() == ReadCodeAiProperties.Queue.Mode.RABBIT) {
            int requeued = jobs.requeueRunningJobs(REQUEUE_MESSAGE);
            if (requeued > 0) {
                log.info("启动对账：{} 个运行中的任务已改回排队 —— 消息没被 ack，队列会重新投递它们", requeued);
            }
            List<Long> stale = jobs.findStaleQueued(STALE_QUEUED_MINUTES);
            if (!stale.isEmpty()) {
                log.warn("有 {} 个任务排队超过 {} 分钟仍未被消费：{} —— 消息可能已丢失（检查 DLQ 与消费者），"
                        + "需要的话请重新提交", stale.size(), STALE_QUEUED_MINUTES, stale);
            }
            return requeued;
        }

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
        enqueue(new IndexTask(jobId, IndexTask.Type.LOCAL, root.toString()));
        return jobs.find(jobId).orElseThrow();
    }

    /** 提交一个 **GitHub 链接**索引任务：仓库行要等拉取完才知道根路径。 */
    public IndexJob submitRemote(String gitUrl) {
        long jobId = jobs.create("GIT", gitUrl);
        jobs.start(jobId, null, "QUEUED", "排队中");
        enqueue(new IndexTask(jobId, IndexTask.Type.GIT, gitUrl));
        return jobs.find(jobId).orElseThrow();
    }

    /**
     * 提交一个**压缩包**索引任务：压缩包先落到工作区（快），解压与索引都在后台做。
     *
     * <p>消息里带的是**工作区里的路径**（不是 HTTP 上传的临时文件）—— 否则进程重启后
     * 消息还在、文件没了。多实例部署时这个路径必须在共享存储上（单机多 worker 不涉及）。
     */
    public IndexJob submitArchive(Path archive, String originalFilename) {
        String label = originalFilename == null ? archive.getFileName().toString() : originalFilename;
        long jobId = jobs.create("ARCHIVE", label);
        jobs.start(jobId, null, "QUEUED", "排队中");
        enqueue(new IndexTask(jobId, IndexTask.Type.ARCHIVE, archive.toString()));
        return jobs.find(jobId).orElseThrow();
    }

    /** 队列里还有多少任务（界面与测试都用得上）。 */
    public int queued() {
        return queue.queued();
    }

    /** 正在跑的任务数。 */
    public int running() {
        return queue.running();
    }

    /**
     * 投递：两种队列的失败方式不一样，都要**明确**（任务表里看得见原因），不能悄悄吞掉。
     */
    private void enqueue(IndexTask task) {
        try {
            queue.enqueue(task);
        } catch (RejectedExecutionException e) {
            // 进程内队列满了 = 明确的"忙"，而不是无限堆积（堆积只会让所有任务都变慢）
            jobs.finish(task.jobId(), "FAILED", "FAILED", "索引队列已满（最多 8 个排队），请稍后再提交");
            throw new IllegalStateException("索引队列已满，请稍后再提交（当前排队 " + queue.queued() + " 个）");
        } catch (AmqpException e) {
            // broker 不可达：任务没被投出去，如实失败并给出可操作的提示（换模式或修 broker）
            jobs.finish(task.jobId(), "FAILED", "FAILED", shortMessage("消息中间件不可达：" + e.getMessage()));
            throw new IllegalStateException("消息中间件（RabbitMQ）不可达，任务未投递："
                    + "请检查 broker，或把 readcodeai.queue.mode 改回 in-process");
        }
    }
}
