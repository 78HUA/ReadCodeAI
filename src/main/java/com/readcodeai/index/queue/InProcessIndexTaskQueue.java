package com.readcodeai.index.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 进程内队列：**默认实现**，零依赖。
 *
 * <p>它就是原来 {@code AsyncIndexer} 里那个线程池，原样搬过来 —— 行为一个字没变，
 * 只是换了个位置：这样"进程内 vs MQ"的对照实验里，两边跑的是**同一套执行逻辑**（{@link IndexTaskRunner}），
 * 差异只剩"任务怎么送过来"。
 *
 * <p>并发度 1：**这是默认值，但实测它并不是最优**（2026-09-20，三个仓库 23.8 万 + 17.9 万 + 3.8 万行：
 * 并发 1 → 38 秒，并发 3 → 24 秒）。早先"并发索引只会互相拖慢"的说法来自**单任务内**的观察
 * （第二个仓库排队等前一个），在"多个仓库排队"的场景下不成立 —— 里面的解析已经是多线程的，
 * 落库也在等 IO，多开一两个 worker 能把等待填满。要调大就调 `readcodeai.queue.concurrency`（MQ 模式）。
 * 队列容量 8：满了就明确拒绝（"已有索引任务在跑"），而不是无限堆积。
 */
public class InProcessIndexTaskQueue implements IndexTaskQueue {

    private static final Logger log = LoggerFactory.getLogger(InProcessIndexTaskQueue.class);

    private final IndexTaskRunner runner;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8),
            runnable -> {
                Thread thread = new Thread(runnable, "index-worker");
                thread.setDaemon(true);
                return thread;
            });

    public InProcessIndexTaskQueue(IndexTaskRunner runner) {
        this.runner = runner;
    }

    @Override
    public void enqueue(IndexTask task) {
        executor.execute(() -> {
            try {
                runner.run(task);
            } catch (RuntimeException | LinkageError e) {
                // 失败原因已经由 runner 写进任务表（状态 FAILED），这里只留一行日志，不让线程死掉
                log.debug("进程内任务失败（原因已写入 index_job）：{}", task.describe());
            }
        });
    }

    @Override
    public int queued() {
        return executor.getQueue().size();
    }

    @Override
    public int running() {
        return executor.getActiveCount();
    }

    @Override
    public String describe() {
        return "进程内队列（单 worker · 队列容量 8 · **重启会丢任务**，需要不丢就用 readcodeai.queue.mode=rabbit）";
    }

    /** 供测试与关闭时使用：不再接受新任务（已排队的照常跑完）。 */
    public void shutdown() {
        executor.shutdown();
    }
}
