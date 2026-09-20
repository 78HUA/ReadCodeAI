package com.readcodeai.verify;

import com.readcodeai.config.ReadCodeAiProperties;
import com.readcodeai.index.AsyncIndexer;
import com.readcodeai.index.ProjectIndexer;
import com.readcodeai.index.model.IndexJob;
import com.readcodeai.index.queue.RabbitIndexTaskQueue;
import com.readcodeai.index.store.IndexJobRepository;
import com.readcodeai.retrieve.SymbolQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 索引任务 MQ 化的**真实验证**（需要一个跑着的 RabbitMQ，默认跳过）。
 *
 * <p>它要证明三件事（前两件是这一步的全部价值所在）：
 * <ol>
 *   <li><b>端到端能跑</b>：发布 → 消费 → 任务 READY。</li>
 *   <li><b>重启续跑</b>：消费者不在时提交，消息**留在队列里**；消费者一起来就接着干。
 *       进程内队列的对照是：同样的场景任务被标 FAILED、要人工重提交（那条对照在离线测试里）。</li>
 *   <li><b>失败不无限重试</b>：坏消息进死信队列（DLQ），队列不被打转的消息堵死。</li>
 * </ol>
 *
 * <p>门槛是"broker 在不在"而不是某个开关：连不上 RabbitMQ 时，队列 Bean 会降级成进程内实现，
 * 这些用例就自动跳过（与 Redis 相关的用例同一套做法 —— 依赖在就测，不在就跳过，不留一堆默认跳过的死用例）。
 *
 * <p>队列名默认 {@code readcodeai.index.jobs}；每个用例开始会**清空自己的两个队列**，
 * 不碰 broker 上别的队列。
 */
@SpringBootTest(properties = {
        "readcodeai.queue.mode=RABBIT",
        "readcodeai.index.workspace=target/test-workspace-rabbit",
        // **用自己的队列名**：否则会跟"正在运行的应用实例"抢同一个队列 ——
        // 实测踩过：应用也在消费，测试暂停自己的消费者没用，消息被应用取走，断言看到 DONE 而不是 QUEUED
        "readcodeai.queue.name=readcodeai.index.jobs.test",
        "readcodeai.queue.dlq-name=readcodeai.index.jobs.test.dlq"
})
class RabbitIndexLiveTest {

    @Autowired
    private AsyncIndexer asyncIndexer;

    @Autowired
    private com.readcodeai.index.queue.IndexTaskQueue queue;

    @Autowired
    private IndexJobRepository jobs;

    @Autowired
    private ProjectIndexer indexer;

    @Autowired
    private SymbolQueryService queries;

    @Autowired
    private AmqpAdmin admin;

    @Autowired
    private ReadCodeAiProperties properties;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private org.springframework.amqp.rabbit.connection.ConnectionFactory connectionFactory;

    @org.junit.jupiter.api.io.TempDir
    Path tempDir;

    private RabbitIndexTaskQueue rabbit() {
        assumeTrue(queue instanceof RabbitIndexTaskQueue, "当前不是 rabbit 模式的队列，跳过");
        return (RabbitIndexTaskQueue) queue;
    }

    @AfterEach
    void deleteReposThisTestCreated() {
        // 断言失败时测试自己那行 deleteIndex 走不到 —— 这里按临时目录前缀兜底，界面才不会被测试垃圾污染
        int deleted = TestRepoCleanup.deleteReposUnder(jdbc, tempDir.toString().replace(java.io.File.separatorChar, '/') + "%");
        if (deleted > 0) {
            System.out.printf("[MQ·清理] 删掉本次测试造的 %d 个仓库行%n", deleted);
        }
    }

    @BeforeEach
    void clearMyQueuesAndMakeSureConsumingIsOn() {
        RabbitIndexTaskQueue rabbit = rabbit();
        // 只清自己的队列：broker 上别的队列（别的项目留下的）一根汗毛都不动
        admin.purgeQueue(rabbit.queueName());
        admin.purgeQueue(rabbit.dlqName());
        // 顺序无关：上一个用例（暂停消费那个）万一半路失败，不能让后面的用例跟着死
        rabbit.resumeConsuming();
    }

    @Test
    void endToEndPublishConsumeReady() throws IOException, InterruptedException {
        Path repo = tinyJavaRepo("mq-e2e");

        long start = System.nanoTime();
        IndexJob job = asyncIndexer.submitLocal(repo);
        long submitMillis = (System.nanoTime() - start) / 1_000_000;

        IndexJob finished = waitFor(job.id(), Duration.ofSeconds(90));
        long totalMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(finished.status()).isEqualTo("READY");
        assertThat(queries.requireRepo(finished.repoId()).fileCount()).isGreaterThan(0);
        System.out.printf("%n[MQ·端到端] 提交 %d ms → 任务 %d %s（%s）· 端到端 %d ms · 队列：%s%n",
                submitMillis, job.id(), finished.status(), finished.message(), totalMillis, queue.describe());

        indexer.deleteIndex(finished.repoId());
    }

    @Test
    void aTaskSubmittedWhileNobodyConsumesSurvivesAndFinishesWhenTheConsumerComesBack() throws Exception {
        RabbitIndexTaskQueue rabbit = rabbit();
        Path repo = tinyJavaRepo("mq-restart");

        // ① 消费者不在（模拟进程死亡/未启动）
        //    注意既有语义：`jobs.start(...)` 把**状态**写成 RUNNING，排队与否看 **stage** —— 别把两者搞混
        rabbit.pauseConsuming();
        try {
            IndexJob job = asyncIndexer.submitLocal(repo);
            Thread.sleep(1000);   // 给 broker 一点时间把消息落盘

            IndexJob whilePaused = jobs.find(job.id()).orElseThrow();
            int queuedMessages = rabbit.queued();
            assertThat(whilePaused.stage()).as("没有消费者时任务停在排队阶段").isEqualTo("QUEUED");
            assertThat(queuedMessages).as("消息应当留在队列里（这就是'不丢'）").isGreaterThanOrEqualTo(1);

            // ② 重启对账：MQ 模式下**不标失败**（对照：进程内模式会把未完成的任务标 FAILED）
            asyncIndexer.reconcileUnfinishedJobs();
            assertThat(jobs.find(job.id()).orElseThrow().status())
                    .as("MQ 模式的对账不该把任务标失败").isNotEqualTo("FAILED");
            System.out.printf("%n[MQ·重启续跑] 消费者不在时提交 → stage=%s · 队列里留着 %d 条消息 · 对账后状态 %s%n",
                    whilePaused.stage(), queuedMessages, jobs.find(job.id()).orElseThrow().status());

            // ③ 消费者回来 → 自动接着干（真实重启时，未 ack 的消息就是这样被重投的）
            rabbit.resumeConsuming();
            IndexJob finished = waitFor(job.id(), Duration.ofSeconds(90));
            assertThat(finished.status()).isEqualTo("READY");
            System.out.printf("[MQ·重启续跑] 消费者启动后自动跑完：%s（%s）—— 进程内队列在同样场景下是 FAILED%n",
                    finished.status(), finished.message());

            indexer.deleteIndex(finished.repoId());
        } finally {
            // 这个用例动了"消费开关"，任何失败都要把它放回原位，否则后面的用例会被一起拖死
            rabbit.resumeConsuming();
        }
    }

    @Test
    void aPoisonMessageGoesToTheDeadLetterQueueInsteadOfSpinningForever() throws Exception {
        RabbitIndexTaskQueue rabbit = rabbit();
        int before = rabbit.deadLettered();

        // 直接往队列里塞一条"任务不存在"的消息（业务上永远不会成功的那种）
        new org.springframework.amqp.rabbit.core.RabbitTemplate(connectionFactory)
                .convertAndSend(rabbit.queueName(),
                        "{\"jobId\":999999999,\"type\":\"LOCAL\",\"payload\":\"/x\"}");

        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline && rabbit.deadLettered() <= before) {
            Thread.sleep(200);
        }
        assertThat(rabbit.deadLettered()).as("坏消息应当落进死信队列").isGreaterThan(before);
        long drainDeadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < drainDeadline && rabbit.queued() > 0) {
            Thread.sleep(200);
        }
        assertThat(rabbit.queued()).as("主队列不该被这条消息一直占着（不无限重试）").isZero();
        System.out.printf("%n[MQ·死信] 坏消息 → DLQ %d 条（处理前 %d）· 主队列剩余 %d 条%n",
                rabbit.deadLettered(), before, rabbit.queued());
    }

    // ---- helpers ----

    private Path tinyJavaRepo(String suffix) throws IOException {
        Path root = tempDir.resolve("repo-" + suffix);
        Path src = root.resolve("src/main/java/demo");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Mq.java"), """
                package demo;
                public class Mq {
                    public int twice(int x) { return x + x; }
                }
                """, StandardCharsets.UTF_8);
        return root;
    }

    private IndexJob waitFor(long jobId, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        IndexJob job = jobs.find(jobId).orElseThrow();
        while (System.nanoTime() < deadline && !List.of("READY", "FAILED").contains(job.status())) {
            Thread.sleep(200);
            job = jobs.find(jobId).orElseThrow();
        }
        return job;
    }
}
