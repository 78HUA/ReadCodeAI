package com.readcodeai.eval;

import com.readcodeai.agent.model.AnsweredBy;
import com.readcodeai.agent.model.AskAnswer;
import com.readcodeai.agent.model.AskEvidence;
import com.readcodeai.agent.model.VerificationSummary;
import com.readcodeai.eval.model.GeneratedQuestion;
import com.readcodeai.eval.model.QType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 判卷口径的纯逻辑测试（不连数据库、不连模型）。
 *
 * <p>判卷如果错了，后面所有指标都是假的 —— 所以这一层要先单独钉死：
 * **多报要扣分、少报要扣分、定位题多列同名符号不该扣分。**
 */
class AutoGraderTest {

    private final AutoGrader grader = new AutoGrader();

    @Test
    void locateHitsWhenTheAnswerContainsTheTargetPosition() {
        var question = question(QType.LOCATE, List.of("src/A.java:10"));

        var grade = grader.grade(question, answer("src/A.java", 10));

        assertThat(grade.hit()).isTrue();
        assertThat(grade.f1()).isEqualTo(1.0);
    }

    @Test
    void locateStillHitsWhenExtraSameNamedSymbolsAreListed() {
        // 「X 定义在哪」而仓库里有 3 个同名符号时，多列出另外两个是**正确行为**，不该扣分
        var question = question(QType.LOCATE, List.of("src/A.java:10"));

        var grade = grader.grade(question, answer("src/A.java", 10, "src/B.java", 20));

        assertThat(grade.hit()).as("定位题看的是「包不包含」，不是「集合相等」").isTrue();
    }

    @Test
    void locateMissesWhenTheTargetPositionIsNotIncluded() {
        var question = question(QType.LOCATE, List.of("src/A.java:10"));

        var grade = grader.grade(question, answer("src/B.java", 20));

        assertThat(grade.hit()).isFalse();
        assertThat(grade.note()).contains("未包含目标位置");
    }

    @Test
    void setQuestionsScoreMissingAndExtraAnswersSeparately() {
        var question = question(QType.CALLERS, List.of("src/A.java:1", "src/B.java:2", "src/C.java:3"));

        // 只答对一个，漏两个
        var conservative = grader.grade(question, answer("src/A.java", 1));
        assertThat(conservative.hit()).isFalse();
        assertThat(conservative.precision()).as("报出来的都是对的").isEqualTo(1.0);
        assertThat(conservative.recall()).as("但只召回三分之一").isCloseTo(1.0 / 3, org.assertj.core.data.Offset.offset(0.001));

        // 全答对但多报一个
        var overReporting = grader.grade(question, answer(
                "src/A.java", 1, "src/B.java", 2, "src/C.java", 3, "src/D.java", 4));
        assertThat(overReporting.hit()).isFalse();
        assertThat(overReporting.precision()).isEqualTo(0.75);
        assertThat(overReporting.recall()).isEqualTo(1.0);

        // 完全一致
        var exact = grader.grade(question, answer("src/A.java", 1, "src/B.java", 2, "src/C.java", 3));
        assertThat(exact.hit()).isTrue();
        assertThat(exact.f1()).isEqualTo(1.0);
    }

    @Test
    void aQuestionWithoutGroundTruthIsReportedRatherThanSilentlyCountedAsHit() {
        var grade = grader.grade(new GeneratedQuestion(QType.CALLERS, "q", "{}", List.of(), "{}"),
                answer("src/A.java", 1));

        assertThat(grade.hit()).isFalse();
        assertThat(grade.note()).contains("没有标准答案");
    }

    private static GeneratedQuestion question(QType type, List<String> truth) {
        return new GeneratedQuestion(type, "问题", "{}", truth, "{}");
    }

    /** 便捷构造：{@code answer("src/A.java", 10, "src/B.java", 20)} 表示两条证据。 */
    private static AskAnswer answer(Object... fileLinePairs) {
        List<AskEvidence> evidence = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < fileLinePairs.length; i += 2) {
            String file = String.valueOf(fileLinePairs[i]);
            int line = Integer.parseInt(String.valueOf(fileLinePairs[i + 1]));
            evidence.add(new AskEvidence(file, line, line, "", "why"));
        }
        return new AskAnswer("答案", evidence, false, null, AnsweredBy.STATIC,
                List.of(), 0, 0, 0, 0, 1, VerificationSummary.none());
    }
}
