package com.readcodeai.index.queue;

/**
 * 索引任务的投递方式：**进程内队列**或**消息队列**，由 {@code readcodeai.queue.mode} 选。
 *
 * <p>抽成接口不是"为了将来可能换"，而是现在同时留着两条路，各有明确用途：
 * <ul>
 *   <li><b>进程内</b>（默认）：零依赖、开箱即用 —— 没装 broker 的机器上项目照常跑。
 *       代价写在 README 已知限制里：重启丢任务。</li>
 *   <li><b>RabbitMQ</b>：任务持久化、重启后自动续跑、消费者可扩到多个 —— 这是它买得到的东西。</li>
 * </ul>
 * 两条路**共用同一套任务状态表与执行逻辑**（{@link IndexTaskRunner}），所以差别只在"怎么送过去"，
 * 不在"怎么做"—— 这也是能做前后对照实验的前提。
 */
public interface IndexTaskQueue {

    /**
     * 投递一个任务。**投递成功不等于执行成功**：状态看 {@code index_job} 表。
     *
     * @throws java.util.concurrent.RejectedExecutionException 进程内队列满了（明确的"忙"，不堆积）
     */
    void enqueue(IndexTask task);

    /** 排队中的任务数（进程内 = 队列长度；MQ = broker 上 ready 的消息数，尽力而为）。 */
    int queued();

    /** 正在执行的任务数。 */
    int running();

    /** 日志与界面用的一句话。 */
    String describe();
}
