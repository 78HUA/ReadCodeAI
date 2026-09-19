package com.readcodeai.agent.cache;

import com.readcodeai.config.ReadCodeAiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 缓存装配：**连不上就降级，绝不阻断启动**（与 LLM 客户端同一套纪律）。
 *
 * <p>启动时做一次 ping 只为"日志里说清到底有没有缓存"，之后每次读写各自兜异常 ——
 * 因为 Redis 可能在运行期间才挂掉，那种情况下问答必须照常。
 */
@Configuration
public class AnswerCacheConfig {

    private static final Logger log = LoggerFactory.getLogger(AnswerCacheConfig.class);

    @Bean
    AnswerCache answerCache(RedisConnectionFactory connectionFactory,
                            ReadCodeAiProperties properties) {
        if (!properties.getCache().isEnabled()) {
            log.warn("答案缓存已关闭（readcodeai.cache.enabled=false）：问答照常，只是每次都会真算一遍");
            return new NoopAnswerCache("readcodeai.cache.enabled=false");
        }
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        AnswerCache cache = new RedisAnswerCache(template, properties.getCache());
        try {
            // ping 只是为了让日志说实话，不影响装配结果
            template.execute((org.springframework.data.redis.core.RedisCallback<String>) connection ->
                    connection.ping());
            log.info("答案缓存已启用：{}", cache.describe());
        } catch (RuntimeException e) {
            // 起不来也照样装配：第一次读写会再试一次，失败也只是"不缓存"
            log.warn("Redis 当前不可达（{}）：缓存暂时不可用，问答照常（每次都会真算）", e.getMessage());
        }
        return cache;
    }
}
