package com.readcodeai.api;

/**
 * 调用模型的接口的限流器（**令牌桶**）。
 *
 * <p>限的是"会花钱、会占额度"的那几个接口：一次问答要花几千 token、几十秒，
 * 没有闸门的话一个循环脚本就能把额度打满、把索引/问答全拖慢。
 *
 * <p>为什么是令牌桶而不是"每分钟固定次数"：问答的耗时差异很大（几秒到几十秒），
 * 桶能让偶尔的突发（连着问三个问题）通过，但只要持续超过补充速率就会被拦住 ——
 * 这比死板的窗口计数更贴合真实使用。
 *
 * <p>实现放在 Redis 里（Lua 保证原子）的原因：**限流必须跨实例**。
 * 单实例内存计数在多实例部署下等于每台机器各限一次，总流量会是配置值的 N 倍。
 */
public interface RateLimiter {

    /**
     * 取一个令牌。
     *
     * @return 允许则返回剩余令牌数（≥0）；被限流返回 {@link Decision#limited} 且带上要等多久
     */
    Decision tryAcquire(String key);

    /** 当前实现是否真的在限流（Redis 不可用时为 false：**放行**并告警，见实现里的取舍说明）。 */
    boolean enabled();

    String describe();

    /** 判定结果：剩余多少、被限时还要等几秒。 */
    record Decision(boolean allowed, int remaining, long retryAfterSeconds) {

        static Decision allowed(int remaining) {
            return new Decision(true, remaining, 0);
        }

        static Decision limited(int remaining, long retryAfterSeconds) {
            return new Decision(false, remaining, retryAfterSeconds);
        }
    }
}
