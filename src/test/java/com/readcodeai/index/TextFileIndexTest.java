package com.readcodeai.index;

import com.readcodeai.index.store.IndexJobRepository;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.TextRetriever;
import com.readcodeai.retrieve.model.ChunkHit;
import com.readcodeai.retrieve.model.RepoView;
import com.readcodeai.verify.TestCorpus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 文本文件（配置 / SQL / 文档）**只做检索**的验收。
 *
 * <p>要钉住四件事，缺一条这个功能就会悄悄污染别的数字：
 * <ol>
 *   <li>关键词能搜到：{@code application.yml} 里的一个词能被全文检索命中（这是这个功能的全部意义）</li>
 *   <li>**不进解析统计**：解析成功率仍然只算 Java（文本文件不是"被解析"）</li>
 *   <li>**不进模块划分**：摘要页"模块的文件数之和 == 仓库文件数"不能因为多了 pom.xml 而失真</li>
 *   <li>**不产生假答案**：文本文件没有符号，所以"谁调用了它"这类问题不会因为它们多出结果</li>
 * </ol>
 */
@SpringBootTest
class TextFileIndexTest {

    @Autowired
    private ProjectIndexer indexer;

    @Autowired
    private SymbolQueryService queries;

    @Autowired
    private TextRetriever textRetriever;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private IndexJobRepository jobs;

    @org.junit.jupiter.api.io.TempDir
    Path tempDir;

    @AfterEach
    void deleteReposThisTestCreated() {
        // 只删临时目录下的：真实语料（gson 等）的仓库行要留着给别的用例用
        com.readcodeai.verify.TestRepoCleanup.deleteReposUnder(jdbc,
                tempDir.toString().replace(java.io.File.separatorChar, '/') + "%");
    }

    @Test
    void textFilesAreSearchableButStayOutOfTheJavaNumbers() throws IOException {
        Path repo = sampleRepoWithConfig();

        IndexSummary summary = indexer.index(repo);
        System.out.printf("%n[文本检索] Java %d 个文件（解析成功 %d）· 文本文件 %d 个 · 检索单元 %d 个%n",
                summary.fileCount(), summary.parsedOkCount(), summary.textFileCount(), summary.chunkCount());

        assertThat(summary.fileCount()).as("Java 文件数只数 Java").isEqualTo(1);
        assertThat(summary.textFileCount()).as("yml / sql / md 应当被收录为可检索文本").isGreaterThanOrEqualTo(3);
        assertThat(summary.parseSuccessRate()).as("解析成功率只算 Java（文本文件不该拉高或拉低它）").isEqualTo(1.0);

        // ① 能搜到：这两个词**只出现在文本文件里**，Java 源码里没有
        List<ChunkHit> ymlHits = textRetriever.search(summary.repoId(), "spring.datasource.hikari", 5);
        assertThat(ymlHits).as("配置文件的片段应当能被检索到")
                .anySatisfy(hit -> assertThat(hit.filePath()).endsWith("application.yml"));
        List<ChunkHit> sqlHits = textRetriever.search(summary.repoId(), "CREATE TABLE customer_order", 5);
        assertThat(sqlHits).as("SQL 文件的片段应当能被检索到")
                .anySatisfy(hit -> assertThat(hit.filePath()).endsWith("schema.sql"));

        // ② 块的 kind 是 TEXT（符号检索/向量那边只取 SYMBOL，不会被混进来）
        Integer textChunks = jdbc.queryForObject("""
                SELECT COUNT(*) FROM `chunk` WHERE repo_id = ? AND kind = 'TEXT'
                """, Integer.class, summary.repoId());
        assertThat(textChunks).as("文本块要有自己的 kind，便于与符号块区分").isGreaterThanOrEqualTo(3);
        Integer fileKinds = jdbc.queryForObject("""
                SELECT COUNT(*) FROM `source_file` WHERE repo_id = ? AND kind = 'TEXT'
                """, Integer.class, summary.repoId());
        assertThat(fileKinds).isEqualTo(summary.textFileCount());

        // ③ 不进模块划分的分母：仓库文件数（Java）与文本文件数分开记
        RepoView repoView = queries.requireRepo(summary.repoId());
        assertThat(repoView.fileCount()).as("repo 行里的文件数仍是 Java 文件数").isEqualTo(1);

        // ④ 不产生假答案：文本文件没有符号
        Integer symbolsInTextFiles = jdbc.queryForObject("""
                SELECT COUNT(*) FROM `symbol` s JOIN `source_file` f ON f.id = s.file_id
                 WHERE s.repo_id = ? AND f.kind = 'TEXT'
                """, Integer.class, summary.repoId());
        assertThat(symbolsInTextFiles).as("文本文件不该有任何符号（否则会污染调用图与定位）").isZero();

        indexer.deleteIndex(summary.repoId());
    }

    @Test
    void buildOutputAndDependenciesAtTheRepoRootAreExcluded() throws IOException {
        // 回归：Java 的 glob 里 `**/target/**` **匹配不到根目录下的 target/**
        // （`**` 能匹配零字符，但后面的 `/` 要求真有个斜杠）—— 于是构建产物一直进了索引。
        // 后果：检索命中"构建出来的副本"，而它们在索引之后还会被重写 → 证据核验必然对不上
        //（CI 第一次跑抓到的就是这个）。
        Path repo = tempDir.resolve("repo-with-build-output");
        Path src = repo.resolve("src/main/java/demo");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Keep.java"), "package demo;\npublic class Keep {}\n",
                StandardCharsets.UTF_8);

        // 这些都不该进索引：根下的构建产物、前端依赖、子模块里的 target/、打包产物
        for (String junkPath : List.of("target/classes/copied.yml", "node_modules/pkg/index.js",
                "module-a/target/generated.txt", "dist/bundle.js")) {
            Path file = repo.resolve(junkPath);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "must-not-be-indexed\n", StandardCharsets.UTF_8);
        }

        IndexSummary summary = indexer.index(repo);
        List<String> indexedPaths = jdbc.queryForList(
                "SELECT f.path FROM `source_file` f WHERE f.repo_id = ? ORDER BY f.path",
                String.class, summary.repoId());

        System.out.printf("%n[排除规则] 索引到的文件：%s%n", indexedPaths);
        assertThat(indexedPaths).as("构建产物与依赖目录一个都不该进索引")
                .containsExactly("src/main/java/demo/Keep.java");

        indexer.deleteIndex(summary.repoId());
    }

    @Test
    void realCorpusPicksUpItsConfigAndDocs() {
        var corpus = TestCorpus.resolve(indexer, queries);
        assumeTrue(corpus.isPresent(), "语料不存在（sample-repos 或 -Dreadcodeai.verify.repo=），跳过");
        IndexSummary summary = indexer.index(TestCorpus.SAMPLE);

        if (summary.textFileCount() > 0) {
            // gson 这类语料里有 pom.xml / README.md：搜一个只可能出现在它们里的词
            List<ChunkHit> hits = textRetriever.search(summary.repoId(), "maven.compiler.source", 5);
            System.out.printf("[文本检索] %s：文本文件 %d 个 · 搜 pom 配置命中 %d 条%n",
                    summary.name(), summary.textFileCount(), hits.size());
            assertThat(hits).as("真实语料里 pom.xml 的配置项应当能被搜到").isNotEmpty();
        }
    }

    /** 一个 Java 文件 + 三个文本文件（配置 / SQL / 文档）的最小仓库。 */
    private Path sampleRepoWithConfig() throws IOException {
        Path root = tempDir.resolve("repo-with-config");
        Path javaDir = root.resolve("src/main/java/demo");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("Service.java"), """
                package demo;
                public class Service {
                    public String hello() { return "hi"; }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("application.yml"), """
                spring:
                  datasource:
                    url: jdbc:mysql://127.0.0.1:3306/demo
                  datasource:
                    hikari:
                      maximum-pool-size: 10
                """, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("schema.sql"), """
                CREATE TABLE customer_order (
                    id BIGINT PRIMARY KEY,
                    amount DECIMAL(10, 2)
                );
                """, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("README.md"), """
                # Demo 服务

                用 `spring.datasource.hikari` 配置连接池，订单数据存在 `customer_order` 表里。
                """, StandardCharsets.UTF_8);
        return root;
    }
}
