package com.readcodeai.api;

import com.readcodeai.config.ReadCodeAiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 限流的验收（需要本机 Redis，连不上就跳过）。
 *
 * <p>三条：桶空要拒（阈值外）、桶会补（阈值内长期可用）、Redis 挂了要**放行**。
 * 第三条看着"反直觉"，但它是明确的取舍：限流是保护件，保护件故障不该升级成全站不可用。
 */
@SpringBootTest
class RateLimiterTest {

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ReadCodeAiProperties properties;

    @Autowired
    private RateLimiter limiter;

    private RedisTokenBucketRateLimiter bucket(int capacity, int refillPerMinute) {
        return new RedisTokenBucketRateLimiter(redis, props(capacity, refillPerMinute));
    }

    private static ReadCodeAiProperties.RateLimit props(int capacity, int refillPerMinute) {
        ReadCodeAiProperties.RateLimit props = new ReadCodeAiProperties.RateLimit();
        props.setCapacity(capacity);
        props.setRefillPerMinute(refillPerMinute);
        return props;
    }

    private void ensureRedis() {
        try {
            redis.opsForValue().get("readcodeai:rate:probe");
        } catch (RuntimeException e) {
            assumeTrue(false, "Redis 不可达（" + e.getClass().getSimpleName() + "），跳过限流用例");
        }
    }

    @Test
    void theBucketAllowsBurstsThenRejects() {
        ensureRedis();
        String key = "test-burst-" + System.nanoTime();
        RedisTokenBucketRateLimiter limiter = bucket(3, 60);   // 容量 3，每分钟补 1 个

        assertThat(limiter.tryAcquire(key).allowed()).as("桶满时前 3 次必须放行").isTrue();
        assertThat(limiter.tryAcquire(key).allowed()).isTrue();
        assertThat(limiter.tryAcquire(key).allowed()).isTrue();

        RateLimiter.Decision fourth = limiter.tryAcquire(key);
        assertThat(fourth.allowed()).as("桶空了要拒").isFalse();
        assertThat(fourth.retryAfterSeconds()).as("被拒时要告诉客户端等多久").isGreaterThan(0);
        System.out.printf("%n[限流] 容量 3 · 第 4 次被拒（等 %d 秒）%n", fourth.retryAfterSeconds());
        limiter.reset(key);
    }

    @Test
    void theBucketRefillsOverTime() throws InterruptedException {
        ensureRedis();
        String key = "test-refill-" + System.nanoTime();
        RedisTokenBucketRateLimiter limiter = bucket(1, 6000);   // 每分钟补 6000 = 每秒 100 个

        assertThat(limiter.tryAcquire(key).allowed()).isTrue();
        assertThat(limiter.tryAcquire(key).allowed()).as("刚取空，立刻再取应当被拒").isFalse();

        Thread.sleep(1100);   // 等一秒，补充约 100 个（上限是容量 1）
        assertThat(limiter.tryAcquire(key).allowed()).as("补过之后应当又能取").isTrue();
        limiter.reset(key);
    }

    @Test
    void theInterceptorRejectsWith429ShapedExceptionAndReportsRemaining() {
        ensureRedis();
        String clientIp = "10.9.9." + (System.nanoTime() % 250);
        ReadCodeAiProperties.RateLimit props = props(1, 60);
        RateLimitInterceptor interceptor =
                new RateLimitInterceptor(new RedisTokenBucketRateLimiter(redis, props));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ask");
        request.setRemoteAddr(clientIp);
        var response = new org.springframework.mock.web.MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).as("第一次放行").isTrue();
        assertThat(response.getHeader("X-RateLimit-Limit")).isNotNull();

        assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                .isInstanceOf(RateLimitedException.class)
                .hasMessageContaining("请求过于频繁");
        assertThat(response.getHeader("Retry-After")).as("被拒时要回带 Retry-After").isNotNull();
        interceptorField(interceptor).reset("ip:" + clientIp);
    }

    /** 取出拦截器里的 limiter（只为清掉测试键，避免污染）。 */
    private static RedisTokenBucketRateLimiter interceptorField(RateLimitInterceptor interceptor) {
        try {
            java.lang.reflect.Field field = RateLimitInterceptor.class.getDeclaredField("limiter");
            field.setAccessible(true);
            return (RedisTokenBucketRateLimiter) field.get(interceptor);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void forwardedForHeaderWinsOverTheSocketAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ask");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
        assertThat(RateLimitInterceptor.clientKey(request))
                .as("网关后面取真实来源，而不是网关自己的地址").isEqualTo("ip:203.0.113.7");
    }

    @Test
    void redisBeingDownMeansOpenGateNotBrokenService() {
        var brokenFactory = new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(
                "127.0.0.1", 6399);
        brokenFactory.afterPropertiesSet();
        try {
            RedisTokenBucketRateLimiter broken =
                    new RedisTokenBucketRateLimiter(new StringRedisTemplate(brokenFactory), props(1, 1));
            assertThat(broken.enabled()).as("连不上时 enabled() 如实报 false").isFalse();
            for (int i = 0; i < 5; i++) {
                assertThat(broken.tryAcquire("any").allowed())
                        .as("Redis 挂了要 fail-open：放行（保护件故障 ≠ 全站不可用）").isTrue();
            }
            System.out.printf("%n[限流] Redis 不可达 → 连续 5 次全部放行（fail-open，告警一次）%n");
        } finally {
            brokenFactory.destroy();
        }
    }

    @Test
    void theBeanInTheContainerIsWiredTheWayConfigurationSays() {
        ensureRedis();
        // 容器里的那个（由配置决定）：默认启用且是 Redis 实现
        assertThat(limiter).isInstanceOf(RedisTokenBucketRateLimiter.class);
        assertThat(limiter.describe()).contains("令牌桶");
    }
}
