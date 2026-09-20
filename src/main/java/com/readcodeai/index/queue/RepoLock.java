package com.readcodeai.index.queue;

/**
 * 按**仓库路径**的互斥锁：同一个仓库不能有两个索引任务同时跑。
 *
 * <h3>它防的是什么（与幂等的分工）</h3>
 * <ul>
 *   <li><b>幂等</b>（{@link IndexTaskRunner}）防的是"同一条消息被投递两次" —— 同一个 job 重跑无害。</li>
 *   <li><b>这把锁</b>防的是"**两条不同的任务指向同一个仓库路径**"：索引是"删了再插"的覆盖语义，
 *       两个任务同时做就会互相拆台（一个刚插完的符号被另一个删掉）。这是数据库的 claim 表达不了的不变量，
 *       也不是幂等能管的 —— 因为那确实是两个不同的任务。</li>
 * </ul>
 *
 * <p>什么时候会真的撞上：消费者并发数 &gt; 1 时（{@code readcodeai.queue.concurrency}），
 * 或者同一个仓库被连续提交两次而第一条还在跑。单 worker 时撞不上，但锁的成本几乎为零，留着更稳。
 */
public interface RepoLock {

    /**
     * 拿锁（等不到就等，等到超时为止）。
     *
     * @return 拿到了返回"锁句柄"（必须 {@code close()} 释放）；超时返回空
     */
    java.util.Optional<Handle> acquire(String repoRootPath);

    /** 当前实现是否真的能互斥（Redis 不可用时为 false，调用方据此决定要不要告警）。 */
    boolean available();

    /** 日志与界面用的一句话。 */
    String describe();

    /** 持有的锁：{@code close()} 释放（Lua 比对 token，删别人的锁是不可能的）。 */
    interface Handle extends AutoCloseable {
        @Override
        void close();

        /** 等了多久才拿到（毫秒）—— 有竞争时这个数字要能被看见。 */
        long waitedMillis();
    }
}
