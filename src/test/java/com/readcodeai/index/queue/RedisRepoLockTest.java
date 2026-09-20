package com.readcodeai.index.queue;

import com.readcodeai.config.ReadCodeAiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 仓库锁的验收（需要本机 Redis，连不上就跳过）。
 *
 * <p>三条语义各对应一个真实事故，不是"跑通就行"的用例：
 * <ol>
 *   <li><b>释放只删自己的锁</b>：锁过期 → 别人拿到 → 自己醒来把别人的锁删了 —— 经典事故，靠 Lua 比对 token 挡住。</li>
 *   <li><b>等不到就超时</b>：别人正在索引同一仓库时，第二个任务要等（不是立刻失败，也不是无限等）。</li>
 *   <li><b>持有期间会续期</b>：索引跑几分钟，锁不能在干活途中自己过期。</li>
 * </ol>
 */
@SpringBootTest
class RedisRepoLockTest {

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ReadCodeAiProperties properties;

    private RedisRepoLock lock() {
        ensureRedis();
        ReadCodeAiProperties.Lock props = new ReadCodeAiProperties.Lock();
        props.setTtlSeconds(3);
        props.setRefreshSeconds(1);
        props.setWaitSeconds(1);
        return new RedisRepoLock(redis, props);
    }

    private void ensureRedis() {
        try {
            redis.opsForValue().get("readcodeai:lock:probe");
        } catch (RuntimeException e) {
            assumeTrue(false, "Redis 不可达（" + e.getClass().getSimpleName() + "），跳过仓库锁用例");
        }
    }

    @Test
    void releasingOnlyRemovesOurOwnLock() {
        RedisRepoLock lock = lock();
        String resource = "test-own-lock-" + System.nanoTime();
        RedisRepoLock.Handle handle = lock.acquire(resource).orElseThrow();
        String key = RedisRepoLock.keyFor(resource);

        // 模拟"我们的锁过期了、别人拿到了"：直接把 key 改成别人的 token
        redis.opsForValue().set(key, "别人的token", Duration.ofSeconds(30));
        handle.close();

        assertThat(redis.opsForValue().get(key))
                .as("释放时 token 不匹配就不能删 —— 否则会把别人的锁删掉（经典事故）")
                .isEqualTo("别人的token");
        redis.delete(key);
    }

    @Test
    void aSecondAcquireTimesOutInsteadOfWaitingForever() {
        RedisRepoLock lock = lock();
        String resource = "test-timeout-" + System.nanoTime();
        RedisRepoLock.Handle first = lock.acquire(resource).orElseThrow();

        long start = System.nanoTime();
        Optional<RedisRepoLock.Handle> second = lock.acquire(resource);
        long waitedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(second).as("同一资源第二次获取应当超时返回空").isEmpty();
        assertThat(waitedMillis).as("等待时间应当接近配置的 1 秒").isGreaterThanOrEqualTo(900);

        first.close();
        assertThat(lock.acquire(resource)).as("释放之后应当又能拿到").isPresent();
        System.out.printf("%n[仓库锁] 第二次获取等了 %d ms 后超时返回空（配置 1 秒）%n", waitedMillis);
    }

    @Test
    void theLockIsRenewedWhileWeHoldIt() throws InterruptedException {
        RedisRepoLock lock = lock();   // TTL 3 秒，每 1 秒续期
        String resource = "test-renew-" + System.nanoTime();
        RedisRepoLock.Handle handle = lock.acquire(resource).orElseThrow();
        String key = RedisRepoLock.keyFor(resource);

        Thread.sleep(5000);   // 远超 TTL：没有续期的话锁早就没了

        assertThat(redis.opsForValue().get(key))
                .as("持有期间必须续期，否则索引跑到一半锁就过期了（等于没锁）")
                .isNotBlank();
        handle.close();
        assertThat(redis.opsForValue().get(key)).as("释放后应当消失").isNull();
        System.out.printf("%n[仓库锁] 持有 5 秒（TTL 3 秒 · 每 1 秒续期）后锁仍在，释放后消失%n");
    }

    @Test
    void redisBeingDownDegradesToFailOpenWithAWarning() {
        // 指向一个没人听的端口：构造一个"连不上"的客户端
        var brokenFactory = new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(
                "127.0.0.1", 6399);
        brokenFactory.afterPropertiesSet();
        try {
            RedisRepoLock broken = new RedisRepoLock(new StringRedisTemplate(brokenFactory), properties.getLock());
            assertThat(broken.available()).as("连不上时 available() 必须如实报 false").isFalse();
            assertThat(broken.acquire("any-resource"))
                    .as("Redis 挂了要 fail-open（索引不能因为加固件挂掉就停摆）").isPresent();
            System.out.printf("%n[仓库锁] Redis 不可达 → available=false 且 acquire 放行（fail-open，告警一次）%n");
        } finally {
            brokenFactory.destroy();
        }
    }
}
