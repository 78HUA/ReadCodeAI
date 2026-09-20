package com.readcodeai.index.queue;

import com.readcodeai.config.ReadCodeAiProperties;
import com.readcodeai.index.AsyncIndexer;
import com.readcodeai.index.ProjectIndexer;
import com.readcodeai.index.model.IndexJob;
import com.readcodeai.index.store.IndexJobRepository;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.RepoView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 任务执行器的验收（**离线，不需要 broker**）：幂等、按类型复原步骤、失败传播、两种模式的对账差异。
 *
 * <p>为什么这些必须在离线跑得动：它们全是"MQ 语义"里最容易悄悄错的地方 ——
 * at-least-once 必然重投（幂等）、消息里只有数据（按类型重放）、失败要能被 MQ 看见（抛异常而不是吞）、
 * 重启后的状态语义（有队列 vs 没队列）。把这些交给"要起 broker 才能测"的用例，等于不测。
 *
 * <p>工作区改到 {@code target/}：测试不该往用户目录里的工作区写东西。
 */
@SpringBootTest(properties = "readcodeai.index.workspace=target/test-workspace-queue")
class IndexTaskRunnerTest {

    @Autowired
    private IndexTaskRunner runner;

    @Autowired
    private IndexTaskQueue queue;

    @Autowired
    private AsyncIndexer asyncIndexer;

    @Autowired
    private IndexJobRepository jobs;

    @Autowired
    private ProjectIndexer indexer;

    @Autowired
    private SymbolQueryService queries;

    @Autowired
    private ReadCodeAiProperties properties;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void deleteReposThisTestCreated() {
        int deleted = com.readcodeai.verify.TestRepoCleanup.deleteReposUnder(jdbc,
                tempDir.toString().replace(java.io.File.separatorChar, '/') + "%", "%test-workspace-queue%");
        if (deleted > 0) {
            System.out.printf("[队列·清理] 删掉本次测试造的 %d 个仓库行%n", deleted);
        }
    }

    @Test
    void runningTheSameTaskTwiceIndexesOnlyOnce() throws IOException, InterruptedException {
        Path repo = tinyJavaRepo("idem");
        IndexJob job = asyncIndexer.submitLocal(repo);
        IndexJob finished = waitFor(job.id(), Duration.ofSeconds(60));
        assertThat(finished.status()).isEqualTo("READY");
        RepoView firstIndex = queries.requireRepo(job.repoId());
        var indexedAtBefore = firstIndex.indexedAt();

        // 同一条消息被重投（at-least-once 下这是必然会发生的）：执行器必须原地跳过
        runner.run(new IndexTask(job.id(), IndexTask.Type.LOCAL, repo.toString()));

        IndexJob afterSecondRun = jobs.find(job.id()).orElseThrow();
        assertThat(afterSecondRun.status()).as("重复投递不该改动已完成任务的状态").isEqualTo("READY");
        assertThat(queries.requireRepo(job.repoId()).indexedAt())
                .as("重复投递不该真的重索引一次（indexed_at 是索引时间戳，重跑会变）")
                .isEqualTo(indexedAtBefore);
        System.out.printf("%n[队列·幂等] 任务 %d 重复执行一次 → 状态仍 %s · 索引时间戳未变（没有白跑一遍）%n",
                job.id(), afterSecondRun.status());

        indexer.deleteIndex(job.repoId());
    }

    @Test
    void archiveTaskIsExtractedAndIndexedByTheRunner() throws IOException, InterruptedException {
        Path archive = tempDirFile("demo.zip");
        writeZip(archive, "demo/src/main/java/Demo.java", """
                package demo;
                public class Demo {
                    public int add(int a, int b) { return a + b; }
                }
                """);

        IndexJob job = asyncIndexer.submitArchive(archive, "demo.zip");
        IndexJob finished = waitFor(job.id(), Duration.ofSeconds(60));

        assertThat(finished.status()).isEqualTo("READY");
        // 压缩包任务的 repo_id 是**消费时**才有的（解压完才知道根路径），所以要读完成后的那一行
        RepoView repo = queries.requireRepo(finished.repoId());
        assertThat(repo.fileCount()).as("压缩包里的 Java 文件应当被索引到").isGreaterThanOrEqualTo(1);
        System.out.printf("%n[队列·压缩包] 任务 %d → %s（%s）· 仓库 %d 个文件%n",
                job.id(), finished.status(), finished.message(), repo.fileCount());

        indexer.deleteIndex(finished.repoId());
    }

    @Test
    void aFailingTaskIsMarkedFailedAndTheExceptionEscapesForTheBroker() {
        // 消息指向一个不存在的任务：这种消息只能进死信队列（重试多少次都不会变好）
        assertThatThrownBy(() -> runner.run(new IndexTask(999_999_999L, IndexTask.Type.LOCAL, "/tmp/none")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("任务不存在");

        // 路径不存在：索引会失败，任务必须被写成 FAILED（表里能看到原因），异常继续抛给 MQ
        IndexJob job = jobs.find(jobs.create("LOCAL", "/not/a/real/path")).orElseThrow();
        jobs.start(job.id(), null, "QUEUED", "测试");
        assertThatThrownBy(() -> runner.run(new IndexTask(job.id(), IndexTask.Type.LOCAL, "/not/a/real/path")))
                .isInstanceOf(RuntimeException.class);

        IndexJob failed = jobs.find(job.id()).orElseThrow();
        assertThat(failed.status()).as("失败要落在表里（使用者看表就知道为什么）").isEqualTo("FAILED");
        assertThat(failed.message()).isNotBlank();
        System.out.printf("%n[队列·失败] 任务 %d → %s（%s）—— 异常继续抛给 MQ，由它送死信队列%n",
                job.id(), failed.status(), abbreviate(failed.message()));
    }

    @Test
    void reconciliationKeepsRunningJobsAliveWhenAQueueIsBehindThem() {
        ReadCodeAiProperties.Queue.Mode original = properties.getQueue().getMode();
        try {
            // 有队列：重启后未 ack 的消息会被重投，所以 RUNNING 改回 QUEUED（**不标失败**）
            properties.getQueue().setMode(ReadCodeAiProperties.Queue.Mode.RABBIT);
            long requeueJob = createJobWithStatus("RUNNING");
            asyncIndexer.reconcileUnfinishedJobs();
            assertThat(jobs.find(requeueJob).orElseThrow().status())
                    .as("MQ 模式：重启后运行中的任务改回排队，等消息重投")
                    .isEqualTo("QUEUED");

            // 没有队列：任务真的丢了，只能如实标失败并提示重新提交（旧行为，作为对照保留）
            properties.getQueue().setMode(ReadCodeAiProperties.Queue.Mode.IN_PROCESS);
            long lostJob = createJobWithStatus("RUNNING");
            asyncIndexer.reconcileUnfinishedJobs();
            assertThat(jobs.find(lostJob).orElseThrow().status())
                    .as("进程内模式：任务丢了，如实标失败")
                    .isEqualTo("FAILED");

            System.out.printf("%n[队列·对账] 同一段代码：MQ 模式 → QUEUED（等重投）· 进程内模式 → FAILED（丢了）%n");
        } finally {
            properties.getQueue().setMode(original);
        }
    }

    @Test
    void theConfiguredQueueIsWhatWeExpectInTests() {
        // 测试环境用默认的进程内队列（否则跑一次测试就要一个 broker）
        assertThat(queue).isInstanceOf(InProcessIndexTaskQueue.class);
        assertThat(queue.describe()).contains("进程内");
    }

    @Test
    void aJobWithoutRepoComesBackWithNullNotZero() {
        // 回归：`rs.wasNull()` 只反映**最近一次**读取的列。写成
        // `new IndexJob(rs.getLong("id"), rs.wasNull() ? null : repoId, ...)` 会让
        // repo_id=NULL 被读成 0 —— 而 0 会被拿去写外键，报 "Cannot add or update a child row"。
        long jobId = jobs.create("GIT", "https://github.com/example/repo");
        IndexJob job = jobs.find(jobId).orElseThrow();
        assertThat(job.repoId()).as("还没拉取完的任务没有 repo_id，必须是 null（不是 0）").isNull();
    }

    @Test
    void longFailureMessagesAreTruncatedSoTheFailureItselfCanBeRecorded() {
        // 回归：数据库异常的 message 里带着整条 SQL，超过 message 列（255）会让"标记失败"这一步
        // 也写失败 —— 结果是任务永远卡在 RUNNING。所有写库的 message 都要过这道截断。
        String longMessage = "x".repeat(1000);
        assertThat(IndexTaskRunner.shortMessage(longMessage)).hasSizeLessThanOrEqualTo(243);
        assertThat(IndexTaskRunner.shortMessage(null)).isNull();
        assertThat(IndexTaskRunner.shortMessage("短消息")).isEqualTo("短消息");
    }

    // ---- helpers ----

    @org.junit.jupiter.api.io.TempDir
    Path tempDir;

    private long createJobWithStatus(String status) {
        long jobId = jobs.create("LOCAL", "test-" + status);
        if ("RUNNING".equals(status)) {
            jobs.start(jobId, null, "PARSING", "测试：假装正在跑");
        }
        return jobId;
    }

    private Path tempDirFile(String name) {
        return tempDir.resolve(name);
    }

    private Path tinyJavaRepo(String suffix) throws IOException {
        Path root = tempDir.resolve("repo-" + suffix);
        Path src = root.resolve("src/main/java/demo");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Hello.java"), """
                package demo;
                public class Hello {
                    public String greet() { return "hi"; }
                    public String greetTwice() { return greet() + greet(); }
                }
                """, StandardCharsets.UTF_8);
        return root;
    }

    private void writeZip(Path zip, String entryName, String content) throws IOException {
        Files.createDirectories(zip.getParent());
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry(entryName));
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    private IndexJob waitFor(long jobId, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        IndexJob job = jobs.find(jobId).orElseThrow();
        while (System.nanoTime() < deadline && !List.of("READY", "FAILED").contains(job.status())) {
            Thread.sleep(100);
            job = jobs.find(jobId).orElseThrow();
        }
        return job;
    }

    private static String abbreviate(String text) {
        return text == null ? "(无)" : (text.length() <= 80 ? text : text.substring(0, 80) + "...");
    }
}
