package com.readcodeai.evidence;

import com.readcodeai.agent.model.AskEvidence;
import com.readcodeai.retrieve.model.ChunkHit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 定向补检索：**校验没过之后不是笼统重试，而是按失败类型分别处理**。
 *
 * <p>纠正的锚点是「我们实际发给模型的那些片段」—— 这是"定向"的含义：
 * 行号记错了、路径写短了，都能拿真实片段纠回来；
 * 而**引的代码与磁盘完全对不上**这种（编造）纠不回来，只能丢掉并计数。
 *
 * <p>还有一条更重要的规则在调用方：**修正过的候选必须再过一遍校验** ——
 * 不能因为"我修过了"就当成通过。这里直接测了这一点。
 */
class EvidenceRepairTest {

    private static final String REAL_FILE = """
            package p;

            public class Real {
                public void target() {
                    int answer = 42;
                }
            }
            """;

    private final EvidenceVerifier verifier = new EvidenceVerifier();
    private final EvidenceRepair repair = new EvidenceRepair();

    @TempDir
    Path repoRoot;

    @Test
    void repairsWrongLineNumbersUsingTheChunkThatWasSentToTheModel() throws IOException {
        write("src/Real.java", REAL_FILE);
        List<ChunkHit> sent = List.of(chunk("src/Real.java", 4, 6));

        var firstPass = verifier.verify(root(), List.of(
                new AskEvidence("src/Real.java", 900, 920, "", "行号记错了")));
        assertThat(firstPass.allValid()).isFalse();

        var result = repair.repair(firstPass, sent);
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).startLine()).isEqualTo(4);
        assertThat(result.candidates().get(0).endLine()).isEqualTo(6);
        System.out.printf("%n[修正] %s%n", result.actions());

        // 修正后必须真的能通过校验
        assertThat(verifier.verify(root(), result.candidates()).allValid()).isTrue();
    }

    @Test
    void repairsAShortenedFilePathAgainstTheSentChunks() throws IOException {
        write("src/main/java/com/example/Real.java", REAL_FILE);
        List<ChunkHit> sent = List.of(chunk("src/main/java/com/example/Real.java", 4, 6));

        var firstPass = verifier.verify(root(), List.of(
                new AskEvidence("Real.java", 4, 6, "", "只写了文件名")));
        assertThat(firstPass.evidence().get(0).failureKind())
                .isEqualTo(EvidenceVerifier.FailureKind.FILE_NOT_FOUND);

        var result = repair.repair(firstPass, sent);
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).file()).isEqualTo("src/main/java/com/example/Real.java");
        System.out.printf("%n[修正] %s%n", result.actions());

        assertThat(verifier.verify(root(), result.candidates()).allValid()).isTrue();
    }

    @Test
    void cannotRepairAFabricatedSnippetAndDropsItInstead() throws IOException {
        write("src/Real.java", REAL_FILE);
        List<ChunkHit> sent = List.of(chunk("src/Real.java", 4, 6));

        var firstPass = verifier.verify(root(), List.of(
                new AskEvidence("src/Real.java", 4, 6,
                        "public void target() { throw new UnsupportedOperationException(); }",
                        "编的片段")));
        assertThat(firstPass.evidence().get(0).failureKind())
                .isEqualTo(EvidenceVerifier.FailureKind.CONTENT_MISMATCH);

        var result = repair.repair(firstPass, sent);

        assertThat(result.candidates()).as("编造纠不回来 —— 只能丢").isEmpty();
        assertThat(result.dropped()).isEqualTo(1);
        System.out.printf("%n[丢弃] %s%n", result.actions());
    }

    @Test
    void aRepairedCandidateIsStillSubjectToVerificationBecauseRepairCanAlsoBeWrong() throws IOException {
        write("src/Real.java", REAL_FILE);
        List<ChunkHit> sent = List.of(chunk("src/Real.java", 4, 6));

        // 行号越界会被修成 4-6，但片段取自文件开头那一段 —— 修完之后内容仍然对不上。
        // 片段要够长（校验器会忽略太短的片段：几个字符在哪儿都可能出现，比中了也说明不了什么）
        var firstPass = verifier.verify(root(), List.of(
                new AskEvidence("src/Real.java", 900, 920, "public class Real {", "片段指向别处")));

        var result = repair.repair(firstPass, sent);
        assertThat(result.candidates()).hasSize(1);

        var secondPass = verifier.verify(root(), result.candidates());
        assertThat(secondPass.allValid())
                .as("修正后仍要过校验 —— 不能因为「我修过了」就当它通过")
                .isFalse();
        System.out.printf("%n[修正后仍未通过] %s%n", secondPass.failureCounts());
    }

    @Test
    void leavesAlreadyValidEvidenceAloneAndReportsNoAction() throws IOException {
        write("src/Real.java", REAL_FILE);

        var report = verifier.verify(root(), List.of(
                new AskEvidence("src/Real.java", 4, 6, "public void target() {", "真证据")));

        var result = repair.repair(report, List.of(chunk("src/Real.java", 4, 6)));

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.changedAnything()).as("没失败就什么都不该动").isFalse();
        assertThat(result.dropped()).isZero();
    }

    private Path root() {
        return repoRoot.toAbsolutePath().normalize();
    }

    private void write(String relative, String content) throws IOException {
        Path file = repoRoot.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static ChunkHit chunk(String file, int start, int end) {
        return new ChunkHit(1L, "SYMBOL", file, start, end, null, null, 10, 1.0, "content");
    }
}
