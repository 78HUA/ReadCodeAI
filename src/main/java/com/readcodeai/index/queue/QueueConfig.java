package com.readcodeai.index.queue;

import com.readcodeai.config.ReadCodeAiProperties;
import com.readcodeai.index.ProjectIndexer;
import com.readcodeai.index.RepoFetcher;
import com.readcodeai.index.store.IndexJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 索引任务队列的装配：**选了 MQ 连不上就明确降级，绝不阻断启动**（与 LLM / Redis / embedding 同一套纪律）。
 *
 * <p>降级这件事这里的取舍是"能跑 > 精确"：
 * 索引本身不依赖 broker，broker 挂了还让整个应用起不来，是把"可选的可靠性增强"变成了"硬依赖"。
 * 但降级必须**大声**：启动日志里写清楚"你配了 rabbit，连不上，已退回进程内队列（代价：重启丢任务）"，
 * 而不是悄悄换掉 —— 静默降级会让"为什么我的任务重启后没了"变成一个查不出来的谜。
 */
@Configuration
public class QueueConfig {

    private static final Logger log = LoggerFactory.getLogger(QueueConfig.class);

    @Bean
    IndexTaskRunner indexTaskRunner(ProjectIndexer indexer, IndexJobRepository jobs,
                                    RepoFetcher repoFetcher, ReadCodeAiProperties properties, RepoLock repoLock) {
        return new IndexTaskRunner(indexer, jobs, repoFetcher, properties, repoLock);
    }

    /**
     * 仓库锁：配了 Redis 就用 Redis（SET NX + Lua 释放 + 后台续期），否则退化为"不互斥"。
     *
     * <p>多 worker（{@code queue.concurrency > 1}）而没有锁时会**大声告警** ——
     * 那种组合下"同一个仓库被并发索引"是真会坏数据的，不能只靠一句文档提醒。
     */
    @Bean
    RepoLock repoLock(ReadCodeAiProperties properties,
                      org.springframework.beans.factory.ObjectProvider<
                              org.springframework.data.redis.connection.RedisConnectionFactory> redisConnections) {
        ReadCodeAiProperties.Lock props = properties.getLock();
        if (!props.isEnabled()) {
            warnIfMultiWorker(properties, "readcodeai.lock.enabled=false");
            return new NoopRepoLock("readcodeai.lock.enabled=false");
        }
        var factory = redisConnections.getIfAvailable();
        if (factory == null) {
            warnIfMultiWorker(properties, "没有 Redis 连接工厂");
            return new NoopRepoLock("未配置 Redis（readcodeai.lock 依赖 Redis）");
        }
        RedisRepoLock lock = new RedisRepoLock(
                new org.springframework.data.redis.core.StringRedisTemplate(factory), props);
        if (!lock.available()) {
            warnIfMultiWorker(properties, "Redis 当前不可达");
            return lock;   // 锁对象照常返回：Redis 恢复后它自己就能用（每次获取都重试）
        }
        log.info("仓库锁已启用：{}", lock.describe());
        return lock;
    }

    private static void warnIfMultiWorker(ReadCodeAiProperties properties, String reason) {
        int concurrency = properties.getQueue().getConcurrency();
        if (concurrency > 1) {
            log.warn("消费者并发数 = {} 但仓库锁不可用（{}）：同一个仓库若被并发索引会互相拆台，"
                    + "请恢复 Redis 或把 readcodeai.queue.concurrency 调回 1", concurrency, reason);
        } else {
            log.info("仓库锁不可用（{}）：单 worker 下不影响（同一时刻只有一个索引任务）", reason);
        }
    }

    @Bean
    IndexTaskQueue indexTaskQueue(IndexTaskRunner runner, IndexJobRepository jobs,
                                  ReadCodeAiProperties properties,
                                  org.springframework.beans.factory.ObjectProvider<ConnectionFactory> connections) {
        ReadCodeAiProperties.Queue props = properties.getQueue();
        if (props.getMode() != ReadCodeAiProperties.Queue.Mode.RABBIT) {
            log.info("索引任务队列：进程内（readcodeai.queue.mode=in-process）—— 零依赖，代价是重启丢任务");
            return new InProcessIndexTaskQueue(runner);
        }

        ConnectionFactory connectionFactory = connections.getIfAvailable();
        if (connectionFactory == null) {
            log.warn("配了 rabbit 模式但容器里没有 RabbitMQ 连接工厂（缺 spring-boot-starter-amqp？），已降级为进程内队列");
            return new InProcessIndexTaskQueue(runner);
        }
        if (!reachable(connectionFactory, props.getConnectTimeoutMs())) {
            log.warn("配了 rabbit 模式但 broker 连不上（{}:{}，{}ms 超时）→ **降级为进程内队列**："
                            + "任务照常能跑，但重启会丢（这是唯一的代价来源）；请检查 RabbitMQ 或改回 in-process",
                    "127.0.0.1", 5672, props.getConnectTimeoutMs());
            return new InProcessIndexTaskQueue(runner);
        }
        return new RabbitIndexTaskQueue(new RabbitTemplate(connectionFactory), new RabbitAdmin(connectionFactory),
                connectionFactory, runner, jobs, props);
    }

    /** 探活：2 秒连不上就当"现在没有 broker"，把选择权交给调用方（降级 + 告警）。 */
    private static boolean reachable(ConnectionFactory connectionFactory, int timeoutMs) {
        try (org.springframework.amqp.rabbit.connection.Connection connection = connectionFactory.createConnection()) {
            return connection != null && connection.isOpen();
        } catch (RuntimeException e) {
            log.debug("broker 探活失败：{}", e.toString());
            return false;
        }
    }
}
