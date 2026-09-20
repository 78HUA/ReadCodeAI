package com.readcodeai.index.queue;

import com.readcodeai.config.ReadCodeAiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis 实现的仓库锁：{@code SET key token NX PX ttl} + **后台续期** + **Lua 比对后删除**。
 *
 * <h3>三个细节都不是装饰</h3>
 * <ol>
 *   <li><b>token + Lua 释放</b>：`SET NX` 只能保证"没人持有"，释放必须"只删自己那把" ——
 *       如果直接 {@code DEL}，会出现"A 的锁过期、B 拿到锁、A 醒来把 B 的锁删了"这种经典事故。
 *       Lua 里比对 token 再删，是原子的一步。</li>
 *   <li><b>续期</b>：索引可能跑几分钟，TTL 必须能被延长，否则锁会在干活途中自己过期
 *       （那等于没锁）。续期线程只在"还持有"时延长，丢了锁就不再续。</li>
 *   <li><b>失败不阻断</b>：Redis 不可用时 {@link #available()} 为 false，拿锁直接放行并**告警一次** ——
 *       索引本身是覆盖语义（重复跑不会写坏结构），锁是防误用的加固；
 *       让"Redis 挂了"升级成"索引完全不能用"是把加固当成了地基（这个取舍写在 README 的降级表里）。</li>
 * </ol>
 */
public class RedisRepoLock implements RepoLock {

    private static final Logger log = LoggerFactory.getLogger(RedisRepoLock.class);

    private static final String PREFIX = "readcodeai:index:lock:";

    /** 只删自己那把锁：token 不匹配就什么都不做（返回 0）。 */
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
              return redis.call('del', KEYS[1])
            else
              return 0
            end
            """, Long.class);

    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
              return redis.call('pexpire', KEYS[1], ARGV[2])
            else
              return 0
            end
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ReadCodeAiProperties.Lock props;
    private final ScheduledExecutorService renewer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "index-lock-renewer");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean unavailableLogged = new AtomicBoolean(false);

    public RedisRepoLock(StringRedisTemplate redis, ReadCodeAiProperties.Lock props) {
        this.redis = redis;
        this.props = props;
    }

    @Override
    public Optional<Handle> acquire(String repoRootPath) {
        String key = keyFor(repoRootPath);
        String token = UUID.randomUUID().toString();
        Duration ttl = Duration.ofSeconds(props.getTtlSeconds());
        long deadline = System.nanoTime() + Duration.ofSeconds(props.getWaitSeconds()).toNanos();
        long start = System.nanoTime();

        while (true) {
            try {
                Boolean got = redis.opsForValue().setIfAbsent(key, token, ttl);
                if (Boolean.TRUE.equals(got)) {
                    long waited = (System.nanoTime() - start) / 1_000_000;
                    if (waited > 500) {
                        log.info("等到仓库锁（等了 {} ms）：{}", waited, repoRootPath);
                    }
                    return Optional.of(new RedisHandle(key, token, waited));
                }
            } catch (RuntimeException e) {
                // Redis 不可用：加固失效，但不该让索引停摆（README 的降级表里写了这个取舍）
                if (unavailableLogged.compareAndSet(false, true)) {
                    log.warn("Redis 不可达，仓库锁暂时失效（索引照常，但同一仓库可能被并发索引）：{}", e.toString());
                }
                return Optional.of(new NoLockHandle((System.nanoTime() - start) / 1_000_000));
            }

            if (System.nanoTime() > deadline) {
                log.warn("等待仓库锁超时（{} 秒）：{} 正被另一个索引任务占着", props.getWaitSeconds(), repoRootPath);
                return Optional.empty();
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
    }

    @Override
    public boolean available() {
        try {
            redis.opsForValue().get(PREFIX + "ping");
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public String describe() {
        return "Redis 仓库锁（key 前缀 " + PREFIX + " · TTL " + props.getTtlSeconds() + "s · 每 "
                + props.getRefreshSeconds() + "s 续期 · 等锁上限 " + props.getWaitSeconds() + "s）";
    }

    /** 锁键：路径可能是中文/带空格的长串，统一取 SHA-256 前 16 位（能看、也够短）。 */
    public static String keyFor(String repoRootPath) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(repoRootPath.getBytes(StandardCharsets.UTF_8));
            return PREFIX + HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 必须提供 SHA-256", e);
        }
    }

    /** 真锁：释放走 Lua，续期交给后台线程。 */
    private final class RedisHandle implements Handle {

        private final String key;
        private final String token;
        private final long waitedMillis;
        private final ScheduledFuture<?> renewal;

        RedisHandle(String key, String token, long waitedMillis) {
            this.key = key;
            this.token = token;
            this.waitedMillis = waitedMillis;
            this.renewal = renewer.scheduleAtFixedRate(this::renew,
                    props.getRefreshSeconds(), props.getRefreshSeconds(), TimeUnit.SECONDS);
        }

        private void renew() {
            try {
                Long renewed = redis.execute(RENEW, List.of(key), token,
                        String.valueOf(props.getTtlSeconds() * 1000));
                if (renewed == null || renewed == 0) {
                    // 锁已经不属于自己（过期后被别人拿走）：停掉续期，别再假装持有
                    log.warn("仓库锁已失效（可能超时后被别的任务拿走）：{}", key);
                    renewal.cancel(false);
                }
            } catch (RuntimeException e) {
                log.debug("锁续期失败（下次再试）：{}", e.toString());
            }
        }

        @Override
        public void close() {
            renewal.cancel(false);
            try {
                redis.execute(RELEASE, List.of(key), token);
            } catch (RuntimeException e) {
                // 释放失败最坏是"锁多活一个 TTL"，比让索引失败强
                log.debug("释放仓库锁失败（会随 TTL 过期）：{}", e.toString());
            }
        }

        @Override
        public long waitedMillis() {
            return waitedMillis;
        }
    }

    /** Redis 不可用时的"空锁"：什么都锁不住，但流程照走（句柄用来保持调用方代码一致）。 */
    private static final class NoLockHandle implements Handle {

        private final long waitedMillis;

        NoLockHandle(long waitedMillis) {
            this.waitedMillis = waitedMillis;
        }

        @Override
        public void close() {
        }

        @Override
        public long waitedMillis() {
            return waitedMillis;
        }
    }
}
