package com.readcodeai.verify;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 第 0 步验证 1：JavaParser 能不能解析真实工程，失败率多少、失败在哪。
 *
 * <p>要的是**可复现的验证**，不是一次性脚本 —— 所以做成测试：
 * 换仓库只要改 {@code -Dreadcodeai.verify.repo=<路径>}。
 * 样例仓库不在时跳过，免得把「环境缺失」误报成「解析失败」。
 */
class ParserToolchainTest {

    /**
     * 语料路径：默认取项目下的 {@code sample-repos/}（已被 .gitignore 排除），
     * 或用 {@code -Dreadcodeai.verify.repo=<路径>} 指定。
     * 这样仓库里不会出现作者本机的目录结构，换语料也不用改代码。
     */
    private static final Path SAMPLE_REPO = Path.of(
            System.getProperty("readcodeai.verify.repo", "sample-repos"));

    private static final ParserConfiguration.LanguageLevel LANGUAGE_LEVEL =
            ParserConfiguration.LanguageLevel.JAVA_21;

    @Test
    void parsesSampleRepositoryAndReportsFailureReasons() throws IOException {
        assumeTrue(Files.isDirectory(SAMPLE_REPO), "样例仓库不存在，跳过：" + SAMPLE_REPO);

        List<Path> javaFiles = collectJavaFiles(SAMPLE_REPO);
        assertThat(javaFiles).as("样例仓库里的 .java 文件").isNotEmpty();

        JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(LANGUAGE_LEVEL));
        Runtime runtime = Runtime.getRuntime();
        long heapBefore = usedHeap(runtime);

        long totalLoc = 0;
        int unreadable = 0;
        int ok = 0;
        Map<String, Integer> failureBuckets = new TreeMap<>();
        List<String> failureSamples = new ArrayList<>();

        long start = System.nanoTime();
        for (Path file : javaFiles) {
            try {
                totalLoc += Files.readAllLines(file).size();
            } catch (MalformedInputException e) {
                unreadable++;
            } catch (IOException e) {
                // 读不出来不影响解析这项统计，交由下面的解析结果反映
            }

            try {
                ParseResult<?> result = parser.parse(file);
                if (result.isSuccessful()) {
                    ok++;
                    continue;
                }
                String reason = result.getProblems().isEmpty()
                        ? "未给出具体问题"
                        : result.getProblems().get(0).getMessage();
                record(failureBuckets, failureSamples, SAMPLE_REPO, file, classify(reason));
            } catch (Exception e) {
                record(failureBuckets, failureSamples, SAMPLE_REPO, file,
                        "异常:" + e.getClass().getSimpleName());
            }
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        long heapAfter = usedHeap(runtime);

        double successRate = (double) ok / javaFiles.size();

        printReport(javaFiles.size(), totalLoc, unreadable, ok, successRate, elapsedMs,
                (heapAfter - heapBefore), failureBuckets, failureSamples);

        assertThat(successRate)
                .as("解析成功率（判据：≥ 95%%）")
                .isGreaterThanOrEqualTo(0.95);
        failureBuckets.forEach((reason, count) ->
                assertThat(reason).as("失败原因必须被归类，不能是「未知」").isNotBlank());
    }

    private static List<Path> collectJavaFiles(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/target/"))
                    .sorted()
                    .toList();
        }
    }

    private static long usedHeap(Runtime runtime) {
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static String classify(String message) {
        if (message.startsWith("Parse error")) {
            return "语法错误(Parse error)";
        }
        return message.length() > 80 ? message.substring(0, 80) + "..." : message;
    }

    private static void record(Map<String, Integer> buckets, List<String> samples,
                               Path root, Path file, String reason) {
        buckets.merge(reason, 1, Integer::sum);
        if (samples.size() < 5) {
            samples.add(root.relativize(file) + "  ->  " + reason);
        }
    }

    private static void printReport(int fileCount, long loc, int unreadable, int ok,
                                    double successRate, long elapsedMs, long heapDelta,
                                    Map<String, Integer> failureBuckets, List<String> failureSamples) {
        StringBuilder sb = new StringBuilder();
        sb.append(System.lineSeparator())
                .append("=== 第 0 步验证 1：JavaParser 解析实测 ===").append(System.lineSeparator())
                .append("样例仓库      : ").append(SAMPLE_REPO).append(System.lineSeparator())
                .append("语言级别      : ").append(LANGUAGE_LEVEL).append(System.lineSeparator())
                .append("Java 文件数   : ").append(fileCount).append(System.lineSeparator())
                .append("代码行数      : ").append(loc).append(System.lineSeparator())
                .append("非 UTF-8 文件 : ").append(unreadable).append(System.lineSeparator())
                .append("解析成功      : ").append(ok).append(System.lineSeparator())
                .append("解析失败      : ").append(fileCount - ok).append(System.lineSeparator())
                .append(String.format("成功率        : %.2f%%%n", successRate * 100))
                .append("耗时          : ").append(elapsedMs).append(" ms").append(System.lineSeparator())
                .append("堆内存增量    : ").append(heapDelta / 1024 / 1024).append(" MB（粗略值，未 GC）")
                .append(System.lineSeparator())
                .append("失败原因分布  :").append(System.lineSeparator());
        if (failureBuckets.isEmpty()) {
            sb.append("  （无失败）").append(System.lineSeparator());
        } else {
            failureBuckets.forEach((reason, count) ->
                    sb.append("  ").append(count).append(" 个  ").append(reason).append(System.lineSeparator()));
        }
        sb.append("失败样例      :").append(System.lineSeparator());
        if (failureSamples.isEmpty()) {
            sb.append("  （无）").append(System.lineSeparator());
        } else {
            failureSamples.forEach(s -> sb.append("  ").append(s).append(System.lineSeparator()));
        }
        System.out.println(sb);
    }
}
