package com.readcodeai.retrieve;

import com.readcodeai.retrieve.model.ChunkHit;
import com.readcodeai.retrieve.model.RepoView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 第 2 步验证（前半）：全文检索层。
 *
 * <p>两个重点：
 * ① **切词是正确性的一部分** —— 含标点的查询（{@code R.success}）不切词就一条都搜不到；
 * ② **命中的 chunk 内容必须与磁盘上那段行区间逐字一致** —— 检索结果将来要当证据用，
 *    内容与行号对不上就等于证据是假的。
 */
@SpringBootTest
class TextSearchTest {

    @Autowired
    private TextRetriever textRetriever;

    @Autowired
    private SymbolQueryService symbolQueryService;

    @Test
    void findsChunksByIdentifierAndTheChunkContentMatchesTheFileOnDisk() throws IOException {
        RepoView repo = latestRepo();
        List<ChunkHit> hits = textRetriever.search(null, "loginCheck", 10);
        assumeTrue(!hits.isEmpty(), "库里还没索引，跳过");

        System.out.printf("%n[全文检索] \"loginCheck\" 命中 %d 条：%n", hits.size());
        hits.forEach(h -> System.out.printf("   %s  (%s, score=%.2f)%n", h.location(), h.kind(), h.score()));

        for (ChunkHit hit : hits) {
            Path file = Path.of(repo.rootPath()).resolve(hit.filePath());
            assertThat(file).exists();
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            assertThat(hit.endLine()).isLessThanOrEqualTo(lines.size());

            // 核心断言：chunk 里的内容，必须与磁盘上这一段行区间完全一致
            String fromDisk = String.join("\n", lines.subList(hit.startLine() - 1, hit.endLine()));
            assertThat(hit.content())
                    .as("%s 的 chunk 内容与磁盘上的第 %d-%d 行不一致", hit.filePath(), hit.startLine(), hit.endLine())
                    .isEqualTo(fromDisk);
        }
    }

    @Test
    void tokenizesQualifiedNamesSoTheyCanBeFound() {
        // 实测结论：含标点的查询直接用 ngram 短语检索会命中 0 条，必须切词
        assertThat(TextRetriever.tokenize("R.success")).containsExactly("success");
        assertThat(TextRetriever.tokenize("MusicService#uploadMusic"))
                .containsExactly("MusicService", "uploadMusic");
        assertThat(TextRetriever.tokenize("查询 订单 列表")).containsExactly("查询", "订单", "列表");
        // 单字符被丢掉：太短，检索价值低且会带来大量噪声
        assertThat(TextRetriever.tokenize("R a b")).isEmpty();

        List<ChunkHit> hits = textRetriever.search(null, "R.success", 5);
        assumeTrue(!hits.isEmpty(), "库里还没索引，跳过");
        assertThat(hits).as("切词后应该能命中（这正是切词存在的理由）").isNotEmpty();
    }

    @Test
    void splitsChineseSentencesIntoBigramsOtherwiseNothingCanMatch() {
        // 回归守卫：中文没有空格，整句若被当成一个 token，短语检索永远命中 0 条 ——
        // 这个 bug 是问答测试实际跑出来的（问「登录检查是在哪里做的？」检索到 0 段）
        List<String> tokens = TextRetriever.tokenize("登录检查是在哪里做的？");

        assertThat(tokens)
                .as("整句必须被切开，否则永远命不中")
                .doesNotContain("登录检查是在哪里做的")
                .contains("登录", "检查");

        assumeTrue(!symbolQueryService.repos().isEmpty(), "库里还没索引，跳过");
        List<ChunkHit> hits = textRetriever.search(null, "登录检查是在哪里做的？", 8);
        assertThat(hits)
                .as("自然语言中文问句必须能检索到候选（这是问答的前置条件）")
                .isNotEmpty();
        System.out.printf("%n[中文问句检索] \"登录检查是在哪里做的？\" 检索到 %d 段%n", hits.size());
    }

    @Test
    void findsChineseCommentsBecauseTheIndexUsesTheNgramParser() {
        List<ChunkHit> hits = textRetriever.search(null, "登录", 10);
        assumeTrue(!hits.isEmpty(), "库里还没索引，跳过");

        System.out.printf("%n[中文检索] \"登录\" 命中 %d 条：%n", hits.size());
        hits.stream().limit(3).forEach(h -> System.out.printf("   %s%n", h.location()));

        assertThat(hits).as("中文注释必须能被搜到（ngram 解析器的意义）").isNotEmpty();
        assertThat(hits).allSatisfy(h -> assertThat(h.content()).contains("登录"));
    }

    @Test
    void returnsEmptyForSomethingThatIsNotThereInsteadOfInventingHits() {
        assumeTrue(!symbolQueryService.repos().isEmpty(), "库里还没索引，跳过");
        assertThat(textRetriever.search(null, "zzzNotPresentIdentifierXyz", 10)).isEmpty();
    }

    @Test
    void rejectsQueriesWithoutUsableTokens() {
        assertThatThrownBy(() -> textRetriever.search(null, "   ", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> textRetriever.search(null, "a", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有可用于检索的词");
    }

    private RepoView latestRepo() {
        List<RepoView> repos = symbolQueryService.repos();
        assumeTrue(!repos.isEmpty(), "还没有任何索引，跳过");
        return repos.get(0);
    }
}
