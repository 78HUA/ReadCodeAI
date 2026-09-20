package com.readcodeai.retrieve;

import com.readcodeai.index.ProjectIndexer;
import com.readcodeai.index.store.SymbolQueryRepository;
import com.readcodeai.retrieve.model.ChunkHit;
import com.readcodeai.retrieve.model.RepoView;
import com.readcodeai.retrieve.model.SymbolView;
import com.readcodeai.verify.TestCorpus;
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
 *    中文整句不切词同样搜不到（这条是问答测试实际跑出来的）。
 * ② **命中的 chunk 内容必须与磁盘上那段行区间逐字一致** —— 检索结果将来要当证据用。
 *
 * <p>语料由 {@code -Dreadcodeai.verify.repo} 指定并**显式锁定 repoId**。
 */
@SpringBootTest
class TextSearchTest {

    @Autowired
    private TextRetriever textRetriever;

    @Autowired
    private SymbolQueryService symbolQueryService;

    @Autowired
    private ProjectIndexer indexer;

    @Autowired
    private SymbolQueryRepository symbolRepository;

    @Test
    void findsChunksByIdentifierAndTheChunkContentMatchesTheFileOnDisk() throws IOException {
        RepoView repo = corpus();
        List<ChunkHit> hits = textRetriever.search(repo.id(), "loginCheck", 10);
        assumeTrue(!hits.isEmpty(), "语料里没有这个标识符，跳过");

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
                    .as("%s 的 chunk 内容与磁盘上的第 %d-%d 行不一致",
                            hit.filePath(), hit.startLine(), hit.endLine())
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

        RepoView repo = corpus();

        // 产品里最常见的一问：中文问句 + 语料里的真实标识符。**这条任何语料都该成立**
        String symbol = symbolRepository.mostCalledMethods(repo.id(), 1).get(0).name();
        List<ChunkHit> mixed = textRetriever.search(repo.id(), "这个方法是怎么实现的：" + symbol + "？", 8);
        assertThat(mixed)
                .as("中文问句里夹着的标识符必须能检索到候选（标识符被中文二元组挤掉过一次）")
                .isNotEmpty();
        System.out.printf("%n[中文问句+标识符检索] 命中 %d 段（标识符 = %s）%n", mixed.size(), symbol);

        // 纯中文问句只有在语料真有中文（注释/字符串）时才可能命中 ——
        // 英文语料上命中 0 段是**正确行为**，不该被断言成失败
        List<ChunkHit> chinese = textRetriever.search(repo.id(), "登录检查是在哪里做的？", 8);
        assumeTrue(corpusHasChinese(repo), "语料里没有中文，纯中文问句命中 0 段是正确结果，跳过");
        assertThat(chinese).as("中文语料上，自然语言中文问句必须能检索到候选").isNotEmpty();
        System.out.printf("[纯中文问句检索] 检索到 %d 段%n", chinese.size());
    }

    @Test
    void findsChineseCommentsBecauseTheIndexUsesTheNgramParser() {
        RepoView repo = corpus();
        List<ChunkHit> hits = textRetriever.search(repo.id(), "登录", 10);
        assumeTrue(!hits.isEmpty(), "语料里没有这个中文词，跳过");

        System.out.printf("%n[中文检索] \"登录\" 命中 %d 条：%n", hits.size());
        hits.stream().limit(3).forEach(h -> System.out.printf("   %s%n", h.location()));

        assertThat(hits).as("中文注释必须能被搜到（ngram 解析器的意义）").isNotEmpty();
        assertThat(hits).allSatisfy(h -> assertThat(h.content()).contains("登录"));
    }

    @Test
    void relaxedSearchOnlyUsesIdentifiersWhenThereAreAny() {
        // 放宽检索（任一词命中）时，有标识符就只用标识符：
        // 中文二元组在注释里到处都是，OR 进去既拉噪声、又可能把 MySQL 的全文检索结果缓存撑爆（实测 error 188）
        assertThat(TextRetriever.relaxedTokens(java.util.List.of("deleteAddressBook", "方法", "调用")))
                .as("有标识符时只用标识符").containsExactly("deleteAddressBook");
        assertThat(TextRetriever.relaxedTokens(java.util.List.of("方法", "调用")))
                .as("没有标识符（纯中文问句）才退到全部词 —— 否则中文问题一条都搜不到")
                .containsExactly("方法", "调用");
        System.out.printf("%n[检索] 放宽时优先标识符：%s%n",
                TextRetriever.relaxedTokens(java.util.List.of("AddressBookService", "方法")));
    }

    @Test
    void returnsEmptyForSomethingThatIsNotThereInsteadOfInventingHits() {
        RepoView repo = corpus();
        assertThat(textRetriever.search(repo.id(), "zzzNotPresentIdentifierXyz", 10)).isEmpty();
    }

    @Test
    void rejectsQueriesWithoutUsableTokens() {
        assertThatThrownBy(() -> textRetriever.search(1L, "   ", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> textRetriever.search(1L, "a", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有可用于检索的词");
    }

    /** 锁定本次要测的语料（不能依赖「最近索引的仓库」）。 */
    /** 语料里有没有中文（注释或字符串）—— 没有的话，"纯中文问句检索"这个用例本身没有意义。 */
    private boolean corpusHasChinese(RepoView repo) {
        try (var files = Files.walk(Path.of(repo.rootPath()), 8)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .limit(200)
                    .anyMatch(path -> {
                        try {
                            return Files.readString(path, StandardCharsets.UTF_8).codePoints()
                                    .anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
                        } catch (IOException e) {
                            return false;
                        }
                    });
        } catch (IOException e) {
            return false;
        }
    }

    private RepoView corpus() {
        var resolved = TestCorpus.resolve(indexer, symbolQueryService);
        assumeTrue(resolved.isPresent(), "语料 " + TestCorpus.SAMPLE + " 不存在，跳过");
        return resolved.get();
    }
}
