package com.readcodeai.verify;

import com.readcodeai.config.ReadCodeAiProperties;
import com.readcodeai.index.IndexSummary;
import com.readcodeai.index.ProjectIndexer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 大仓库基准：**十万行档与更大规模的索引耗时 / 内存拐点**（默认跳过，需显式指定语料）。
 *
 * <p>为什么要有它：三档语料表里"中档（3–6 万行）"一直是空的，而"十万行以上"只有一句
 * "耗时与内存会显著上升"的定性判断 —— 没有数字。这里把它变成可复现的实测。
 *
 * <p>用法（语料是**解压好的目录**，JDK 的 {@code src.zip} 先解开到仓库外）：
 * <pre>
 *   jar xf %JAVA_HOME%/lib/src.zip            # 解到临时目录，别放进仓库
 *   mvn -B test -Dtest=BigCorpusBenchmarkTest \
 *       -Dreadcodeai.verify.bigcorpus=<解压目录>/java.base/java/util \
 *       -DargLine="-Xmx4g"
 * </pre>
 *
 * <p>不指定 {@code readcodeai.verify.bigcorpus} 时直接跳过 —— 它跑一次要几分钟到几十分钟，
 * 不该出现在日常测试里。断言只压"没崩、解析成功率达标、悬挂边为 0"，
 * 耗时与内存**只打印不断言**（它们就是要记录的数字，不是判据）。
 */
@SpringBootTest
class BigCorpusBenchmarkTest {

    private static final Path CORPUS = Path.of(
            System.getProperty("readcodeai.verify.bigcorpus", ""));
    private static final String GATE = "readcodeai.verify.bigcorpus";

    @Autowired
    private ProjectIndexer indexer;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired
    private ReadCodeAiProperties properties;

    @Test
    void indexesALargeCorpusAndReportsCostAndMemory() {
        assumeTrue(System.getProperty(GATE) != null && !System.getProperty(GATE).isBlank(),
                "未指定 -D" + GATE + "=<语料目录>，跳过（大仓库基准不在日常测试里跑）");
        assumeTrue(Files.isDirectory(CORPUS), "语料目录不存在，跳过：" + CORPUS);

        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        long heapBefore = memory.getHeapMemoryUsage().getUsed();
        long maxHeap = memory.getHeapMemoryUsage().getMax();

        System.out.println(System.lineSeparator() + "=== 大仓库基准：" + CORPUS + " ===");
        System.out.printf("堆上限 %.1f GB · 索引前已用 %.1f GB%n",
                maxHeap / 1073741824.0, heapBefore / 1073741824.0);

        // 串行先跑（对照组），并行后跑 —— 顺序对并行有利，所以并行那组的速度数字是**保守下界**
        IndexSummary serial = indexWith(1);
        IndexFingerprint.Fingerprint serialPrint = IndexFingerprint.of(jdbc, serial.repoId());
        long[] peakBytes = new long[1];
        Thread sampler = sampleHeap(peakBytes);
        IndexSummary parallel = indexWith(0);
        sampler.interrupt();
        IndexFingerprint.Fingerprint parallelPrint = IndexFingerprint.of(jdbc, parallel.repoId());

        System.out.println(parallel.toReport());
        System.out.printf("%n串行：解析 %d ms · 解析调用 %d ms · 落库 %d ms · 合计 %d ms%n",
                serial.parseMillis(), serial.resolveMillis(), serial.storeMillis(), serial.totalMillis());
        System.out.printf("并行：解析 %d ms · 解析调用 %d ms · 落库 %d ms · 合计 %d ms（解析提速 %.2fx · 端到端 %.2fx）%n",
                parallel.parseMillis(), parallel.resolveMillis(), parallel.storeMillis(), parallel.totalMillis(),
                parallel.parseMillis() == 0 ? 0.0 : (double) serial.parseMillis() / parallel.parseMillis(),
                parallel.totalMillis() == 0 ? 0.0 : (double) serial.totalMillis() / parallel.totalMillis());
        System.out.printf("堆上限 %.1f GB · 索引过程峰值堆用量 ≈ %.2f GB（每 200 ms 采样）%n",
                maxHeap / 1073741824.0, peakBytes[0] / 1073741824.0);
        System.out.printf("库内规模：符号 %d · 调用边 %d · 检索单元 %d%n",
                parallel.symbolCount(), parallel.callEdgeCount(), parallel.chunkCount());

        assertThat(serialPrint.symbols())
                .as("大语料上并行解析的结果必须与串行逐条一致（每条 万级符号）").isEqualTo(parallelPrint.symbols());
        assertThat(parallelPrint.calls()).isEqualTo(serialPrint.calls());
        assertThat(parallelPrint.chunks()).isEqualTo(serialPrint.chunks());
        assertThat(parallel.parseSuccessRate()).as("解析成功率（判据：≥ 95%）").isGreaterThanOrEqualTo(0.95);
        assertThat(parallel.orphanEdgeCount()).as("悬挂边必须为 0，否则说明分析器有 bug").isZero();
    }

    private IndexSummary indexWith(int parseThreads) {
        int original = properties.getIndex().getParseThreads();
        try {
            properties.getIndex().setParseThreads(parseThreads);
            return indexer.index(CORPUS);
        } finally {
            properties.getIndex().setParseThreads(original);
        }
    }

    /**
     * 每 200 ms 采一次堆用量，把**真实峰值**回填到数组第 0 位。
     *
     * <p>为什么不拿 {@code getCommitted()} 当"峰值"：committed 是 JVM 向系统要了多少，
     * 要过就不还 —— 它只反映 -Xmx，不反映这次索引实际用了多少。峰值必须采样。
     */
    private static Thread sampleHeap(long[] peakBytes) {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        Thread sampler = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                long used = memory.getHeapMemoryUsage().getUsed();
                if (used > peakBytes[0]) {
                    peakBytes[0] = used;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "heap-sampler");
        sampler.setDaemon(true);
        sampler.start();
        return sampler;
    }
}
