package com.readcodeai.agent.cache;

import com.readcodeai.agent.model.AgentAnswer;
import com.readcodeai.config.ReadCodeAiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import tools.jackson.databind.ObjectMapper;

/**
 * Redis 实现的答案缓存。
 *
 * <h3>三条设计约束（每一条都是"缓存这东西最容易出事的地方"）</h3>
 * <ol>
 *   <li><b>坏了不能影响主流程</b>：所有 Redis / 序列化异常都在这里吞掉并记日志 ——
 *       缓存是加速手段，不是正确性依赖</li>
 *   <li><b>键里带索引版本</b>：{@code repoId + indexedAt}，重新索引后自动失效，绝不给过期答案</li>
 *   <li><b>取出来的答案要标成"来自缓存"</b>：{@code cached=true} + 生成时间 + 这次没有花 token ——
 *       悄悄给一份上次的答案是不行的（与摘要语义缓存同一条纪律）</li>
 * </ol>
 *
 * <p>为什么 Redis 只用在"答案缓存"这一处，而不是把摘要缓存也搬过来：
 * 摘要那次查询的代价是"一次 MySQL 查询 ≈ 83ms"，收益太小；而这里省下的是**10–40 秒 + 几千 token**。
 * 中间件要用在刀刃上，见 docs/design-outline.md 的选型表。
 */
public class RedisAnswerCache implements AnswerCache {

    private static final Logger log = LoggerFactory.getLogger(RedisAnswerCache.class);

    private static final String KEY_PREFIX = "readcodeai:answer:";

    /** 同一个键反复出错只记一次日志：缓存故障不该刷屏（刷屏会淹没真正的错误）。 */
    private final AtomicBoolean failureLogged = new AtomicBoolean(false);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Duration ttl;

    public RedisAnswerCache(StringRedisTemplate redis, ReadCodeAiProperties.Cache properties) {
        this.redis = redis;
        this.ttl = Duration.ofMinutes(properties.getAnswerTtlMinutes());
    }

    @Override
    public Optional<AgentAnswer> get(long repoId, String indexedAt, String question, String mode) {
        String key = key(repoId, indexedAt, question, mode);
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            // 原样返回：**"标成来自缓存"由调用方统一做**（见 AnswerCache 的注释）——
            // 否则每个实现都得记得做对这件事，漏一个就会出现"缓存命中了但界面不显示"
            return Optional.of(mapper.readValue(json, AgentAnswer.class));
        } catch (RuntimeException e) {
            warnOnce("取缓存失败（按未命中处理）", e);
            return Optional.empty();
        }
    }

    @Override
    public void put(long repoId, String indexedAt, String question, String mode, AgentAnswer answer) {
        try {
            redis.opsForValue().set(key(repoId, indexedAt, question, mode),
                    mapper.writeValueAsString(answer), ttl);
        } catch (RuntimeException e) {
            warnOnce("写缓存失败（不影响本次回答）", e);
        }
    }

    @Override
    public String describe() {
        return "Redis（键前缀 " + KEY_PREFIX + "，TTL " + ttl.toMinutes() + " 分钟，键里含索引版本）";
    }

    /**
     * 缓存键 = 前缀 + 仓库 + **索引版本** + 问题（含模式）的摘要。
     *
     * <p>问题部分取 SHA-256：问题可能很长（几百字），而 Redis 的键越短越好；
     * 同一个问题只要有一个字符不同就算不同问题，这正是我们要的。
     */
    public static String key(long repoId, String indexedAt, String question, String mode) {
        return KEY_PREFIX + repoId + ":" + (indexedAt == null ? "-" : indexedAt) + ":" + sha256(mode + "\n" + question);
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 必须提供 SHA-256", e);
        }
    }

    private void warnOnce(String message, RuntimeException e) {
        if (failureLogged.compareAndSet(false, true)) {
            log.warn("{}：{}（后续同类错误不再重复记录）", message, e.toString());
        } else {
            log.debug("{}：{}", message, e.toString());
        }
    }
}
