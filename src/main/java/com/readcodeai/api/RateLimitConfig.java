package com.readcodeai.api;

import com.readcodeai.config.ReadCodeAiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 限流的装配：**Redis 可用就真限流，不可用就放行并告警**（与 LLM / 缓存 / 索引锁同一套降级纪律）。
 */
@Configuration
public class RateLimitConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(RateLimitConfig.class);

    /**
     * 装闸门的接口：**会真的花钱/花时间的那四个**。
     *
     * <p>索引提交（{@code /api/repos}）不在里面：它有队列容量兜底，而且提交本身不调模型；
     * 符号查询 / 全文检索也不在里面 —— 它们是本地毫秒级操作，限它们只会让正常操作莫名卡顿。
     */
    static final String[] LIMITED_PATHS = {
            "/api/ask", "/api/agent", "/api/review", "/api/summary"
    };

    /** 用 ObjectProvider 而不是直接注入 RateLimiter：本类里也定义了这个 Bean，直接注入会形成循环。 */
    private final ObjectProvider<RateLimiter> limiterProvider;

    public RateLimitConfig(ObjectProvider<RateLimiter> limiterProvider) {
        this.limiterProvider = limiterProvider;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RateLimitInterceptor(limiterProvider.getObject()))
                .addPathPatterns(LIMITED_PATHS);
    }

    @Bean
    RateLimiter rateLimiter(ReadCodeAiProperties properties,
                            ObjectProvider<RedisConnectionFactory> redisConnections) {
        ReadCodeAiProperties.RateLimit props = properties.getRateLimit();
        if (!props.isEnabled()) {
            log.info("限流已关闭（readcodeai.rate-limit.enabled=false）");
            return new NoopRateLimiter("readcodeai.rate-limit.enabled=false");
        }
        RedisConnectionFactory factory = redisConnections.getIfAvailable();
        if (factory == null) {
            log.warn("限流需要 Redis 但连接工厂不存在 → 放行（保护失效，功能不受影响）");
            return new NoopRateLimiter("未配置 Redis");
        }
        RedisTokenBucketRateLimiter limiter =
                new RedisTokenBucketRateLimiter(new StringRedisTemplate(factory), props);
        if (limiter.enabled()) {
            log.info("限流已启用：{} · 作用路径 {}", limiter.describe(), String.join("、", LIMITED_PATHS));
        } else {
            log.warn("Redis 当前不可达 → 限流暂时放行（功能不受影响，但当前没有闸门）");
        }
        return limiter;
    }
}
