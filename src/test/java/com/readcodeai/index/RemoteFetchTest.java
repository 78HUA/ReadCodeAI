package com.readcodeai.index;

import com.readcodeai.retrieve.SymbolQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 第 3 步验证：远程仓库拉取（「贴个链接就能用」）。
 *
 * <p>真正下载的那条用例是**选择性运行**的（要联网、要几十秒），
 * 用 {@code -Dreadcodeai.verify.remote=<github url>} 打开，
 * 这样常规测试套件不会因为网络抖动而红。
 * 其余几条是纯逻辑断言（SSRF 防线、路径剥离），始终运行。
 */
@SpringBootTest
class RemoteFetchTest {

    @Autowired
    private ProjectIndexer indexer;

    @Autowired
    private RepoFetcher repoFetcher;

    @Autowired
    private SymbolQueryService queryService;

    @Test
    void fetchesFromGitHubAndIndexesTheWholeRepository() {
        String url = System.getProperty("readcodeai.verify.remote");
        assumeTrue(url != null && !url.isBlank(),
                "未指定远程语料，跳过（用 -Dreadcodeai.verify.remote=<github url> 打开）");

        IndexSummary summary = indexer.indexRemote(url);
        System.out.println(System.lineSeparator() + summary.toReport());

        assertThat(summary.commitHash())
                .as("远程索引必须记录「基于哪个提交」，否则无法溯源")
                .matches("[a-f0-9]{40}");
        assertThat(summary.parseSuccessRate()).isGreaterThanOrEqualTo(0.95);
        assertThat(summary.symbolCount()).isGreaterThan(0);
        assertThat(summary.chunkCount()).as("检索单元也该生成").isGreaterThan(0);
        assertThat(summary.orphanEdgeCount()).isZero();

        System.out.printf("%n[远程索引] commit=%s · 文件 %d · 符号 %d · 检索单元 %d · 边 %d · 耗时 %d ms%n",
                summary.commitHash(), summary.fileCount(), summary.symbolCount(),
                summary.chunkCount(), summary.callEdgeCount(), summary.totalMillis());

        // 拉下来的仓库必须能马上被查询层用起来
        assertThat(queryService.repos()).isNotEmpty();
    }

    @Test
    void refusesNonGithubHostsBecauseThatWouldBeAnSsrf() {
        Path workspace = Path.of("target/fetch-reject");

        assertThatThrownBy(() -> repoFetcher.fetch("http://127.0.0.1:8080/owner/repo", workspace))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只接受 https");

        assertThatThrownBy(() -> repoFetcher.fetch("https://127.0.0.1/owner/repo", workspace))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只允许");

        assertThatThrownBy(() -> repoFetcher.fetch("https://example.com/owner/repo", workspace))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只允许");

        assertThatThrownBy(() -> repoFetcher.fetch("https://github.com/onlyowner", workspace))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少 owner/repo");
    }

    @Test
    void parsesOwnerAndRepoOutOfCommonUrlShapes() {
        assertThat(RepoFetcher.parse("https://github.com/google/gson"))
                .isEqualTo(new RepoFetcher.RepositoryRef("google", "gson"));
        assertThat(RepoFetcher.parse("https://github.com/google/gson.git"))
                .isEqualTo(new RepoFetcher.RepositoryRef("google", "gson"));
        assertThat(RepoFetcher.parse("https://github.com/google/gson/tree/main"))
                .isEqualTo(new RepoFetcher.RepositoryRef("google", "gson"));
    }

    @Test
    void stripsTheArchiveTopLevelDirectorySoPathsStayRelativeToTheRepository() {
        assertThat(RepoFetcher.stripTopLevel("gson-HEAD/")).isEmpty();
        assertThat(RepoFetcher.stripTopLevel("gson-HEAD/src/main/java/Gson.java"))
                .isEqualTo("src/main/java/Gson.java");
        // 没有顶层目录的条目直接丢掉（GitHub 的包一定有，防御性处理）
        assertThat(RepoFetcher.stripTopLevel("stray-file.txt")).isNull();
    }
}
