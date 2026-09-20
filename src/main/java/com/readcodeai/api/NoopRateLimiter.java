package com.readcodeai.api;

/**
 * 限流关闭、或 Redis 不可用时的实现：**放行**。
 *
 * <p>与 {@link RedisTokenBucketRateLimiter} 的 fail-open 是同一条理由：
 * 限流是保护件，不是正确性依赖 —— 它坏掉该表现为"没有闸门"，而不是"服务不可用"。
 * 区别只在于：这里会在日志里说明"为什么没有闸门"。
 */
public class NoopRateLimiter implements RateLimiter {

    private final String reason;

    public NoopRateLimiter(String reason) {
        this.reason = reason;
    }

    @Override
    public Decision tryAcquire(String key) {
        return Decision.allowed(Integer.MAX_VALUE);
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public String describe() {
        return "未启用（" + reason + "）：接口不限流";
    }

    public String reason() {
        return reason;
    }
}
