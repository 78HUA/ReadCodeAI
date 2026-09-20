package com.readcodeai.api;

import com.readcodeai.config.ReadCodeAiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis 令牌桶限流：**整个判定在一段 Lua 里完成**（读、补、扣、写、设过期，一步原子）。
 *
 * <h3>为什么必须 Lua</h3>
 * 用 {@code GET} + 计算 + {@code SET} 的话，两个并发请求会读到同一个令牌数、各自扣一次 ——
 * 结果是"配了 10 QPS，实际能过 20"。限流器自己出并发 bug 是最讽刺的一种失败，
 * 所以这里不给自己留解释空间：一段脚本，一次执行。
 *
 * <h3>Redis 挂了怎么办：**放行（fail-open）**</h3>
 * 这是一个明确的取舍，写在代码里也写在 README 里：限流是**保护**（防止额度被打爆），
 * 不是**正确性**（不像索引锁那样防数据被写坏）。Redis 挂掉时如果 fail-closed（一律拒绝），
 * 等于"一个缓存组件故障 → 全站问答不可用"，那比短时限流失效糟糕得多。
 * 放行的同时**告警一次**，让运维知道现在没有闸门。
 */
public class RedisTokenBucketRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketRateLimiter.class);

    private static final String PREFIX = "readcodeai:rate:";

    /** 令牌桶：tokens=当前令牌、ts=上次补充时刻；ARGV = capacity, refillPerMinute, nowMillis。 */
    private static final DefaultRedisScript<List> ACQUIRE = new DefaultRedisScript<>("""
            local capacity = tonumber(ARGV[1])
            local refillPerMs = tonumber(ARGV[2]) / 60000.0
            local now = tonumber(ARGV[3])
            local bucket = redis.call('hmget', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(bucket[1])
            local ts = tonumber(bucket[2])
            if tokens == nil then
              tokens = capacity
              ts = now
            end
            tokens = math.min(capacity, tokens + (now - ts) * refillPerMs)
            local allowed = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            end
            redis.call('hmset', KEYS[1], 'tokens', tokens, 'ts', now)
            -- 桶空之后最多再等这么久就能攒够一个令牌，过期时间取它 + 1 秒
            redis.call('pexpire', KEYS[1], math.ceil(capacity / refillPerMs) + 1000)
            return {allowed, math.floor(tokens)}
            """, List.class);

    private final StringRedisTemplate redis;
    private final ReadCodeAiProperties.RateLimit props;
    private final AtomicBoolean unavailableLogged = new AtomicBoolean(false);

    public RedisTokenBucketRateLimiter(StringRedisTemplate redis, ReadCodeAiProperties.RateLimit props) {
        this.redis = redis;
        this.props = props;
    }

    @Override
    public Decision tryAcquire(String key) {
        String redisKey = PREFIX + key;
        try {
            List<?> result = redis.execute(ACQUIRE, List.of(redisKey),
                    String.valueOf(props.getCapacity()),
                    String.valueOf(props.getRefillPerMinute()),
                    String.valueOf(System.currentTimeMillis()));
            if (result == null || result.size() < 2) {
                return Decision.allowed(props.getCapacity());
            }
            boolean allowed = ((Number) result.get(0)).longValue() == 1;
            int remaining = ((Number) result.get(1)).intValue();
            if (allowed) {
                return Decision.allowed(remaining);
            }
            // 桶空：等一个令牌的时间 = 补充速率的倒数
            long waitSeconds = Math.max(1, (long) Math.ceil(60.0 / props.getRefillPerMinute()));
            return Decision.limited(remaining, waitSeconds);
        } catch (RuntimeException e) {
            // fail-open：详见类注释（保护件故障不该升级成全站不可用）
            if (unavailableLogged.compareAndSet(false, true)) {
                log.warn("Redis 不可达，限流暂时放行（保护失效，功能不受影响）：{}", e.toString());
            }
            return Decision.allowed(props.getCapacity());
        }
    }

    @Override
    public boolean enabled() {
        try {
            redis.opsForValue().get(PREFIX + "probe");
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public String describe() {
        return "Redis 令牌桶（桶容量 " + props.getCapacity() + " · 每分钟补 " + props.getRefillPerMinute()
                + " 个 · key 前缀 " + PREFIX + "）";
    }

    /** 清掉某个键的桶（测试与运维用：手工解除限流）。 */
    public void reset(String key) {
        redis.delete(PREFIX + key);
    }
}
