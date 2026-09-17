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

    private static final Path SAMPLE_REPO = Path.of(
            System.getProperty("readcodeai.verify.repo", "E:/GitHub/yunshu-nas"));

    @Autowired
    private ProjectIndexer indexer;

    @Test
    void indexesSampleRepositoryAndReportsNumbers() {
        assumeTrue(Files.isDirectory(SAMPLE_REPO), "样例仓库不存在，跳过：" + SAMPLE_REPO);

        IndexSummary summary = indexer.index(SAMPLE_REPO);
        System.out.println(System.lineSeparator() + summary.toReport());

        assertThat(summary.fileCount()).isGreaterThan(0);
        assertThat(summary.parsedOkCount())
                .as("解析成功率应达到 100%（云舒NAS 是标准 UTF-8 Maven 工程）")
                .isEqualTo(summary.fileCount());
        assertThat(summary.symbolCount()).as("符号数").isGreaterThan(0);
        assertThat(summary.callEdgeCount()).as("调用边数").isGreaterThan(0);
        assertThat(summary.orphanEdgeCount())
                .as("悬挂边（有调用者调用点、但调用者符号缺失）必须为 0，否则说明分析器有 bug")
                .isZero();
    }
}
