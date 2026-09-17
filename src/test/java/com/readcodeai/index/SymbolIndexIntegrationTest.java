package com.readcodeai.index;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 第 1 步验证：索引真实仓库，把符号表与调用图的规模、解析率作为实测数字产出。
 *
 * <p>要连本机 MySQL（口令走 {@code READCODEAI_DB_PASSWORD} 环境变量）。
 * 索引会**覆盖**库里同名路径的记录 —— 这是设计行为（同一路径重复索引即替换）。
 */
@SpringBootTest
class SymbolIndexIntegrationTest {

    /** 语料路径：默认 {@code sample-repos/}（gitignore 已排除），或用 {@code -Dreadcodeai.verify.repo=<路径>} 指定。 */
    private static final Path SAMPLE_REPO = Path.of(
            System.getProperty("readcodeai.verify.repo", "sample-repos"));

    @Autowired
    private ProjectIndexer indexer;

    @Test
    void indexesSampleRepositoryAndReportsNumbers() {
        assumeTrue(Files.isDirectory(SAMPLE_REPO), "样例仓库不存在，跳过：" + SAMPLE_REPO);

        IndexSummary summary = indexer.index(SAMPLE_REPO);
        System.out.println(System.lineSeparator() + summary.toReport());

        assertThat(summary.fileCount()).isGreaterThan(0);
        assertThat(summary.parseSuccessRate())
                .as("解析成功率（判据：≥ 95%%）")
                .isGreaterThanOrEqualTo(0.95);
        assertThat(summary.symbolCount()).as("符号数").isGreaterThan(0);
        assertThat(summary.callEdgeCount()).as("调用边数").isGreaterThan(0);
        assertThat(summary.orphanEdgeCount())
                .as("悬挂边（有调用点、却找不到宿主方法）必须为 0，否则说明分析器有 bug")
                .isZero();
    }
}
