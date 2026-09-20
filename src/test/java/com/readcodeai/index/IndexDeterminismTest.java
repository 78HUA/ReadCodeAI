package com.readcodeai.index;

import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.RepoView;
import com.readcodeai.verify.IndexFingerprint;
import com.readcodeai.verify.TestCorpus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 落库路径改造的**确定性回归**：同一个语料索引两次，结构必须逐条一致。
 *
 * <p>为什么要这一条：批量化 + 一次 SELECT 回填 id 换掉了"逐条插入边插边查 id"的老写法，
 * 而老写法里藏着两条**顺序语义** —— ① 父符号必须先于子符号出现（否则回填不到 parent_id）；
 * ② 同名符号"后者覆盖前者"（map 覆盖规则）。这两条只要有一条被换掉时的顺序破坏，
 * 现象都是"符号少了一部分"或"父子关系断了"，而且只在**特定语料**上才露头。
 *
 * <p>做法是把"结构"抽成指纹：按 id 顺序的 {@code 类型:名字:文件:起始行:父键} 序列 + 各表计数
 * （id 本身会变，所以不比较 id，比较**顺序里的内容**）。两次索引的指纹必须逐条相等。
 */
@SpringBootTest
class IndexDeterminismTest {

    @Autowired
    private ProjectIndexer indexer;

    @Autowired
    private SymbolQueryService queries;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private com.readcodeai.config.ReadCodeAiProperties properties;

    @Test
    void indexingTheSameCorpusTwiceProducesTheSameStructure() {
        var corpus = TestCorpus.resolve(indexer, queries);
        assumeTrue(corpus.isPresent(), "语料不存在（sample-repos 或 -Dreadcodeai.verify.repo=），跳过");
        var sample = TestCorpus.SAMPLE;

        IndexSummary first = indexer.index(sample);
        RepoView firstRepo = queries.findByRootPath(sample.toAbsolutePath().normalize().toString()).orElseThrow();
        IndexFingerprint.Fingerprint firstPrint = IndexFingerprint.of(jdbc, firstRepo.id());

        IndexSummary second = indexer.index(sample);
        RepoView secondRepo = queries.findByRootPath(sample.toAbsolutePath().normalize().toString()).orElseThrow();
        IndexFingerprint.Fingerprint secondPrint = IndexFingerprint.of(jdbc, secondRepo.id());

        System.out.printf("[确定性回归] 符号 %d · 调用边 %d · 类型关系 %d · 代码块 %d（两次索引规模一致：%s）%n",
                firstPrint.symbols().size(), firstPrint.calls(), firstPrint.relations(),
                firstPrint.chunks(), first.symbolCount() == second.symbolCount());

        assertThat(secondPrint.symbols())
                .as("两次索引的符号序列（类型:名字:文件:行:父键）必须逐条一致 —— 顺序变了就说明回填/覆盖语义被破坏")
                .isEqualTo(firstPrint.symbols());
        assertThat(secondPrint.calls()).isEqualTo(firstPrint.calls());
        assertThat(secondPrint.relations()).isEqualTo(firstPrint.relations());
        assertThat(secondPrint.chunks()).isEqualTo(firstPrint.chunks());
        assertThat(secondPrint.parentNotNull()).as("parent_id 回填的条数也要一致").isEqualTo(firstPrint.parentNotNull());
        assertThat(second.orphanEdgeCount()).as("悬挂边必须为 0").isZero();

        // **不变式（不是"两次一致"而是"必须正确"）**：成员（方法/字段/构造器）一定要有父类型。
        // 为什么单独钉：批量化改造时 parent_id 改成"插完再回填"，第一版用 id 映射定位子行，
        // 遇到**同名符号**就把两行更到同一个 id 上、另一行永远留在 NULL —— 两次索引错得一模一样，
        // 所以"两次一致"的指纹比对看不出问题，是摘要页的"每个文件只算一次"把它抓出来的。
        Integer membersWithoutParent = jdbc.queryForObject("""
                SELECT COUNT(*) FROM `symbol`
                 WHERE repo_id = ? AND parent_id IS NULL AND kind IN ('METHOD', 'CONSTRUCTOR', 'FIELD')
                """, Integer.class, secondRepo.id());
        assertThat(membersWithoutParent).as("成员符号缺父类型：parent_id 回填有 bug").isZero();
    }

    /**
     * 串行 vs 并行：**结果必须逐条一致、速度差要能量出来**（同一个 JVM 里跑，排除 JIT 与磁盘缓存干扰）。
     *
     * <p>这是并行改动最要紧的一条证据：并行只该改"多久跑完"，不该改"跑出什么"。
     * 顺序上让两条路径各跑两次、取各自第二次的数字 —— 第一次都用来热身，不然比的是 JIT 不是并行度。
     */
    @Test
    void serialAndParallelParsingAgreeAndTheSpeedupIsMeasured() {
        var corpus = TestCorpus.resolve(indexer, queries);
        assumeTrue(corpus.isPresent(), "语料不存在（sample-repos 或 -Dreadcodeai.verify.repo=），跳过");
        var sample = TestCorpus.SAMPLE;
        int original = properties.getIndex().getParseThreads();
        try {
            indexWith(sample, 8);   // 热身（并行）
            indexWith(sample, 1);   // 热身（串行）
            IndexSummary parallel = indexWith(sample, 8);
            long parallelParseMs = parallel.parseMillis();
            IndexFingerprint.Fingerprint parallelPrint = IndexFingerprint.of(jdbc, repoIdOf(sample));
            IndexSummary serial = indexWith(sample, 1);
            long serialParseMs = serial.parseMillis();
            IndexFingerprint.Fingerprint serialPrint = IndexFingerprint.of(jdbc, repoIdOf(sample));

            System.out.printf("%n[串行 vs 并行] 解析 %d ms → %d ms（%.2fx）· 端到端 %d ms → %d ms"
                            + " · 符号 %d · 调用边 %d%n",
                    serialParseMs, parallelParseMs,
                    parallelParseMs == 0 ? 0.0 : (double) serialParseMs / parallelParseMs,
                    serial.totalMillis(), parallel.totalMillis(),
                    parallel.symbolCount(), parallel.callEdgeCount());

            assertThat(parallelPrint.symbols())
                    .as("并行解析的结果必须与串行逐条一致（顺序也不能变）").isEqualTo(serialPrint.symbols());
            assertThat(parallelPrint.calls()).isEqualTo(serialPrint.calls());
            assertThat(parallelPrint.relations()).isEqualTo(serialPrint.relations());
            assertThat(parallelPrint.chunks()).isEqualTo(serialPrint.chunks());
            assertThat(parallelPrint.parentNotNull()).isEqualTo(serialPrint.parentNotNull());
        } finally {
            properties.getIndex().setParseThreads(original);
        }
    }

    private IndexSummary indexWith(java.nio.file.Path corpus, int parseThreads) {
        properties.getIndex().setParseThreads(parseThreads);
        return indexer.index(corpus);
    }

    private long repoIdOf(java.nio.file.Path corpus) {
        return queries.findByRootPath(corpus.toAbsolutePath().normalize().toString())
                .orElseThrow().id();
    }
}
