package com.readcodeai.index;

import com.readcodeai.index.model.IndexJob;
import com.readcodeai.index.store.IndexJobRepository;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.RepoView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 异步索引的验收：**接单立刻返回、后台干活、进度能查、重启留下的残局如实标失败**。
 *
 * <p>为什么这几条要单独钉：异步化最容易出的问题不是"跑不起来"，而是
 * ①接口假装很快但其实是同步的；②任务悄悄失败没人知道；③进程重启后仓库永远卡在"索引中"。
 *
 * <p>工作区改到 {@code target/}：测试不该往用户目录里的工作区写东西。
 */
@SpringBootTest(properties = "readcodeai.index.workspace=target/test-workspace-async")
class AsyncIndexTest {

    @Autowired
    private AsyncIndexer asyncIndexer;

    @Autowired
    private ProjectIndexer indexer;

    @Autowired
    private IndexJobRepository jobs;

    @Autowired
    private SymbolQueryService queries;

    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    Path tempDir;

    @Test
    void submitReturnsImmediatelyAndTheJobFinishesInTheBackground() throws IOException, InterruptedException {
        Path repo = tinyJavaRepo();

        long start = System.nanoTime();
        IndexJob job = asyncIndexer.submitLocal(repo);
        long submitMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(submitMillis)
                .as("提交必须立刻返回 —— 索引是分钟级的长任务，同步做就会把请求挂住")
                .isLessThan(2000);
        assertThat(job.repoId()).as("本地路径在提交时就知道，repoId 应当立刻可用").isNotNull();
        assertThat(job.status()).isIn("QUEUED", "RUNNING");

        IndexJob finished = waitFor(job.id(), Duration.ofSeconds(60));
        assertThat(finished.status()).isEqualTo("READY");
        assertThat(finished.stage()).isEqualTo("DONE");
        assertThat(finished.message()).contains("个文件");

        RepoView repo2 = queries.requireRepo(job.repoId());
        assertThat(repo2.status()).isEqualTo("READY");
        assertThat(repo2.fileCount()).isEqualTo(2);
        System.out.printf("%n[异步索引] 提交耗时 %d ms · 任务 %d → %s（%s）%n",
                submitMillis, job.id(), finished.status(), finished.message());

        indexer.deleteIndex(job.repoId());
    }

    @Test
    void progressIsReportedThroughEveryStageWhileIndexing() throws IOException {
        Path repo = tinyJavaRepo();
        long repoId = indexer.createPendingRepo(repo, null);
        List<String> stages = new ArrayList<>();
        List<String> parsedCounts = new ArrayList<>();

        indexer.indexInto(repoId, repo, "tiny", null, (stage, done, total, message) -> {
            if (stages.isEmpty() || !stages.get(stages.size() - 1).equals(stage)) {
                stages.add(stage);
            }
            if ("PARSING".equals(stage)) {
                parsedCounts.add(done + "/" + total);
            }
        });

        System.out.printf("%n[索引进度] 阶段顺序 %s · 解析上报 %s%n", stages, parsedCounts);

        assertThat(stages).as("阶段要按顺序报出来，界面才知道现在在干什么")
                .containsExactly("SCANNING", "PARSING", "STORING", "DONE");
        assertThat(parsedCounts).as("解析阶段要逐文件上报，且最后一个必须是 N/N")
                .isNotEmpty().endsWith("2/2");

        indexer.deleteIndex(repoId);
    }

    @Test
    void aJobThatDiedWithTheProcessIsMarkedFailedInsteadOfStayingRunningForever() {
        // 模拟"上次进程留下的残局"：一条 RUNNING 的任务 + 一个卡在 INDEXING 的仓库
        Path fakeRoot = tempDir.resolve("leftover");
        // 注意：MySQL 没有 INSERT ... RETURNING（那是 PostgreSQL 的语法），插完按 root_path 查回来
        jdbc.update("""
                INSERT INTO `repo` (name, root_path, status, file_count, parsed_ok_count, total_loc,
                                    symbol_count, call_edge_count, call_resolved_count, created_at)
                VALUES ('leftover', ?, 'INDEXING', 0, 0, 0, 0, 0, 0, NOW())
                """, fakeRoot.toString());
        long repoId = jdbc.queryForObject("SELECT id FROM `repo` WHERE root_path = ?", Long.class,
                fakeRoot.toString());
        long jobId = jobs.create("LOCAL", fakeRoot.toString());
        jobs.start(jobId, repoId, "PARSING", "解析中");

        int failed = asyncIndexer.reconcileUnfinishedJobs();

        assertThat(failed).isGreaterThanOrEqualTo(1);
        IndexJob job = jobs.find(jobId).orElseThrow();
        assertThat(job.status()).as("留在 RUNNING 的任务必须被标成失败").isEqualTo("FAILED");
        assertThat(job.message()).as("而且要说明原因（这是「没有队列」的真实代价）").contains("重启");
        assertThat(queries.requireRepo(repoId).status()).as("仓库行也要跟着对账").isEqualTo("FAILED");

        jdbc.update("DELETE FROM `repo` WHERE id = ?", repoId);
    }

    private IndexJob waitFor(long jobId, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        IndexJob last = null;
        while (System.nanoTime() < deadline) {
            last = jobs.find(jobId).orElseThrow();
            if (!"QUEUED".equals(last.status()) && !"RUNNING".equals(last.status())) {
                return last;
            }
            Thread.sleep(150);
        }
        throw new AssertionError("任务在 " + timeout.toSeconds() + " 秒内没有结束，最后状态：" + last);
    }

    /** 造一个最小可索引的仓库（两个 java 文件，够跑完所有阶段）。 */
    private Path tinyJavaRepo() throws IOException {
        Path repo = tempDir.resolve("tiny-repo");
        Path src = repo.resolve("src/main/java/demo");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Hello.java"), """
                package demo;

                public class Hello {
                    public String greet(String name) {
                        return "hi " + name;
                    }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(src.resolve("Caller.java"), """
                package demo;

                public class Caller {
                    public String use() {
                        return new Hello().greet("world");
                    }
                }
                """, StandardCharsets.UTF_8);
        return repo;
    }
}
