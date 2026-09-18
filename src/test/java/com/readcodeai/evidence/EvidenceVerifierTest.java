package com.readcodeai.evidence;

import com.readcodeai.agent.model.AskEvidence;
import com.readcodeai.evidence.EvidenceVerifier.FailureKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **第 4 步验收的核心：证明证据校验拦得住编造。**
 *
 * <p>设计文档里那句话是这个项目的立场：**"我能证明我的防护起作用"比"我加了防护"有说服力得多。**
 * 所以这里不测"校验跑过了"，而是**逐条构造编造的证据，断言它被拦下**。
 *
 * <p>用 {@link TempDir} 造一个可控的仓库，不依赖数据库与语料 —— 这样这些断言永远可复现。
 */
class EvidenceVerifierTest {

    private static final String REAL_FILE = """
            package p;

            public class Real {
                public void target() {
                    int answer = 42;
                }
            }
            """;

    private final EvidenceVerifier verifier = new EvidenceVerifier();

    @TempDir
    Path repoRoot;

    @Test
    void acceptsAVerifiedCitation() throws IOException {
        write("src/Real.java", REAL_FILE);

        var report = verifier.verify(root(), List.of(
                new AskEvidence("src/Real.java", 4, 6, "public void target() {\n    int answer = 42;", "目标方法")));

        assertThat(report.allValid()).isTrue();
        assertThat(report.passed()).isEqualTo(1);
        assertThat(report.evidence().get(0).snippetMatches()).isTrue();
        System.out.printf("%n[校验通过] %s%n", report.evidence().get(0).evidence().location());
    }

    @Test
    void catchesAFabricatedFilePath() throws IOException {
        write("src/Real.java", REAL_FILE);

        var report = verifier.verify(root(), List.of(
                new AskEvidence("src/ThisFileWasInvented.java", 1, 3, "whatever", "编的路径")));

        assertThat(report.allValid()).isFalse();
        assertThat(report.evidence().get(0).failureKind()).isEqualTo(FailureKind.FILE_NOT_FOUND);
        System.out.printf("%n[拦下] 编造的文件路径 → %s%n", report.evidence().get(0).detail());
    }

    @Test
    void catchesLineNumbersBeyondTheEndOfTheFile() throws IOException {
        write("src/Real.java", REAL_FILE);

        var report = verifier.verify(root(), List.of(
                new AskEvidence("src/Real.java", 900, 920, "whatever", "编的行号")));

        assertThat(report.evidence().get(0).failureKind()).isEqualTo(FailureKind.LINE_OUT_OF_RANGE);
        System.out.printf("%n[拦下] 越界行号 → %s%n", report.evidence().get(0).detail());
    }

    @Test
    void catchesAFabricatedSnippetEvenWhenFileAndLinesAreReal() throws IOException {
        write("src/Real.java", REAL_FILE);

        // 这是最要紧的一种：**文件和行号都对，但引的代码是编的**。
        // 只校验文件与行号会放过它 —— 必须真读磁盘比对内容才拦得住。
        var report = verifier.verify(root(), List.of(
                new AskEvidence("src/Real.java", 4, 6,
                        "public void target() { throw new UnsupportedOperationException(); }",
                        "编的片段")));

        assertThat(report.evidence().get(0).failureKind()).isEqualTo(FailureKind.CONTENT_MISMATCH);
        System.out.printf("%n[拦下] 编造的片段（文件与行号都真实）→ %s%n", report.evidence().get(0).detail());
    }

    @Test
    void tamperingWithAValidCitationsLineNumbersIsCaught() throws IOException {
        write("src/Real.java", REAL_FILE);
        AskEvidence genuine = new AskEvidence("src/Real.java", 4, 6,
                "public void target() {\n    int answer = 42;", "真实证据");

        assertThat(verifier.verify(root(), List.of(genuine)).allValid())
                .as("原始证据应当通过").isTrue();

        // 篡改行号：真实的片段配上错误的行号 → 内容比对立刻发现对不上
        AskEvidence tampered = genuine.withRange(1, 3);
        var report = verifier.verify(root(), List.of(tampered));

        assertThat(report.allValid())
                .as("改掉行号之后必须被拦下 —— 这正是「不信模型报的行号」的意义")
                .isFalse();
        assertThat(report.evidence().get(0).failureKind()).isEqualTo(FailureKind.CONTENT_MISMATCH);
        System.out.printf("%n[拦下] 篡改行号 → %s%n", report.evidence().get(0).detail());
    }

    @Test
    void catchesPathsThatEscapeTheRepository() throws IOException {
        write("src/Real.java", REAL_FILE);

        var report = verifier.verify(root(), List.of(
                new AskEvidence("../../../etc/passwd", 1, 2, "root:x:0:0", "越界路径")));

        assertThat(report.evidence().get(0).failureKind()).isEqualTo(FailureKind.PATH_ESCAPES_REPO);
        System.out.printf("%n[拦下] 路径越出仓库 → %s%n", report.evidence().get(0).detail());
    }

    @Test
    void countsEachFailureKindSeparatelySoTheReasonIsVisible() throws IOException {
        write("src/Real.java", REAL_FILE);

        var report = verifier.verify(root(), List.of(
                new AskEvidence("src/Real.java", 4, 6, "public void target() {", "真"),
                new AskEvidence("src/Nope.java", 1, 1, "x", "编路径"),
                new AskEvidence("src/Real.java", 999, 1000, "x", "编行号"),
                new AskEvidence("src/Real.java", 1, 2, "public class SomethingElse {}", "编片段")));

        assertThat(report.evidence()).hasSize(4);
        assertThat(report.passed()).isEqualTo(1);
        assertThat(report.failed()).isEqualTo(3);
        assertThat(report.failureCounts())
                .containsEntry(FailureKind.FILE_NOT_FOUND, 1)
                .containsEntry(FailureKind.LINE_OUT_OF_RANGE, 1)
                .containsEntry(FailureKind.CONTENT_MISMATCH, 1);
        System.out.printf("%n[分类计数] %s%n", report.failureCounts());
    }

    @Test
    void treatsAMissingSnippetAsUnverifiedRatherThanAsAFailure() throws IOException {
        write("src/Real.java", REAL_FILE);

        var report = verifier.verify(root(), List.of(
                new AskEvidence("src/Real.java", 4, 6, "", "模型没给片段")));

        assertThat(report.allValid()).as("文件与行号都对，不该因为少一个字段就判定编造").isTrue();
        assertThat(report.snippetUnverified()).isEqualTo(1);
        assertThat(report.evidence().get(0).snippetMatches()).isNull();
    }

    @Test
    void ignoresSnippetsTooShortToProveAnything() {
        // 太短的片段在哪儿都可能出现，比中了也说明不了什么 —— 按"未核验"处理，不当成通过
        assertThat(EvidenceVerifier.matchSnippet("abc", "abc def ghi")).isNull();
    }

    @Test
    void toleratesQuotesThatOmitLinesInTheMiddleBecauseThatIsHowModelsQuote() {
        // 实测出来的形态：模型引用了注解和方法签名，却把中间的 javadoc 跳过去了。
        // 那是引用的常见样子，不是编造 —— 要求整段连续会把好答案误杀。
        String withJavadoc = """
                @PostMapping("/upload")
                /** 处理上传 */
                public R<String> upload(MultipartFile file) {
                """;
        String quotedWithElision = """
                @PostMapping("/upload")
                public R<String> upload(MultipartFile file) {
                """;

        assertThat(EvidenceVerifier.matchSnippet(quotedWithElision, withJavadoc))
                .as("省略中间几行的真实引用应当通过")
                .isTrue();
    }

    @Test
    void rejectsQuotesAttributedToTheWrongLocationEvenWhenEveryLineIsRealCode() {
        // 实测拦下的最要紧的一类错误：**引的代码是真的，但被安到了错误的文件/行号上**。
        // 逐行比对在这里必然对不上 —— 因为那一行根本不在这个区间里。
        String otherPlace = "orderDetail.setImage(item.getImage());\norderDetail.setAmount(item.getAmount());";
        String quotedFromElsewhere = "orders.setStatus(2); // 设置为已送达状态";

        assertThat(EvidenceVerifier.matchSnippet(quotedFromElsewhere, otherPlace))
                .as("张冠李戴的引用必须被识别出来")
                .isFalse();
    }

    private Path root() {
        return repoRoot.toAbsolutePath().normalize();
    }

    private void write(String relative, String content) throws IOException {
        Path file = repoRoot.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
