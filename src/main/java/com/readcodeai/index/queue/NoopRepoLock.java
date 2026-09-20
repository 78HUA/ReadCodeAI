package com.readcodeai.index.queue;

import java.util.Optional;

/**
 * 锁关掉时的实现：**放行但不假装锁住了**（{@link #available()} 为 false，日志里会说清）。
 *
 * <p>与缓存 / 限流的 Noop 同一个思路：这是加固而不是地基，缺了它功能照常，
 * 但**必须让使用者知道现在是"不互斥"的状态**，否则多 worker 下的并发索引会变成一个查不出来的谜。
 */
public class NoopRepoLock implements RepoLock {

    private final String reason;

    public NoopRepoLock(String reason) {
        this.reason = reason;
    }

    @Override
    public Optional<Handle> acquire(String resource) {
        return Optional.of(new Handle() {
            @Override
            public void close() {
            }

            @Override
            public long waitedMillis() {
                return 0;
            }
        });
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String describe() {
        return "未启用（" + reason + "）：同一仓库被并发索引时没有互斥保护";
    }

    public String reason() {
        return reason;
    }
}
