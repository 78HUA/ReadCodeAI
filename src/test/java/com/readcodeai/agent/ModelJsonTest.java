package com.readcodeai.agent;

import com.readcodeai.agent.model.AskEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型输出契约的解析：**宽容解析、严格校验**。
 *
 * <p>这些用例全部来自实测踩过的坑（小模型的输出不守规矩是常态，不是意外）：
 * Markdown 包裹、非法转义、字段给成 null、把行号写进 file、
 * 结论平铺在顶层而不套 {@code final}。每一条都对应一次真实故障。
 */
class ModelJsonTest {

    @Test
    void splitsALineNumberThatTheModelWroteIntoTheFileField() {
        ModelJson.EvidenceEntry split = ModelJson.splitLineFromFile(
                new ModelJson.EvidenceEntry("src/a/B.java:144", null, null, "", ""));

        assertThat(split.file()).isEqualTo("src/a/B.java");
        assertThat(split.startLine()).isEqualTo(144);
        assertThat(split.endLine()).isEqualTo(144);

        ModelJson.EvidenceEntry range = ModelJson.splitLineFromFile(
                new ModelJson.EvidenceEntry("src/a/B.java:12-30", null, null, "", ""));
        assertThat(range.startLine()).isEqualTo(12);
        assertThat(range.endLine()).isEqualTo(30);
    }

    @Test
    void leavesRealPathsAloneEvenWhenTheyContainAColon() {
        // Windows 盘符后面跟的不是纯数字，所以不会被误当成行号
        ModelJson.EvidenceEntry untouched = ModelJson.splitLineFromFile(
                new ModelJson.EvidenceEntry("C:/work/B.java", 3, 3, "", ""));

        assertThat(untouched.file()).isEqualTo("C:/work/B.java");
        assertThat(untouched.startLine()).isEqualTo(3);
    }

    @Test
    void dropsEvidenceThatCannotBeVerified() {
        List<ModelJson.EvidenceEntry> raw = List.of(
                new ModelJson.EvidenceEntry("src/a/B.java", 1, 2, "", "好证据"),
                new ModelJson.EvidenceEntry(null, 1, 2, "", "缺文件"),
                new ModelJson.EvidenceEntry("src/a/B.java", null, null, "", "缺行号"),
                new ModelJson.EvidenceEntry("src/a/B.java", 5, 3, "", "区间颠倒"),
                new ModelJson.EvidenceEntry("src/a/B.java:7", null, null, "", "行号写在文件里也算"));

        List<AskEvidence> valid = ModelJson.validEvidence(raw);

        assertThat(valid).hasSize(2);
        assertThat(valid).extracting(AskEvidence::file).containsOnly("src/a/B.java");
        assertThat(valid).extracting(AskEvidence::startLine).containsExactly(1, 7);
    }

    @Test
    void parsesJsonWrappedInMarkdownAndFixesIllegalEscapes() {
        String wrapped = """
                ```json
                {"answer":"结论","evidence":[],"refused":false,"refusalReason":""}
                ```
                """;
        ModelJson.SingleHopAnswer first = ModelJson.parse(wrapped, ModelJson.SingleHopAnswer.class);
        assertThat(first.answer()).isEqualTo("结论");

        // 实测踩过：模型在 snippet 里写出 \( 这种非法转义，整道题崩掉
        String illegalEscape = "{\"answer\":\"a\\(b\",\"evidence\":[],\"refused\":false}";
        ModelJson.SingleHopAnswer second = ModelJson.parse(illegalEscape, ModelJson.SingleHopAnswer.class);
        assertThat(second.answer()).isEqualTo("a(b");
    }

    @Test
    void acceptsAConclusionFlattenedAtTheTopLevel() {
        // 模型有时不套 final 那层壳，直接把 answer 平铺出来 —— 为这点差异丢掉一整轮不值得
        ModelJson.Turn turn = ModelJson.parse(
                "{\"thought\":\"够了\",\"answer\":\"结论\",\"evidence\":[{\"file\":\"a/B.java\",\"startLine\":1,"
                        + "\"endLine\":1,\"snippet\":\"\",\"why\":\"x\"}]}",
                ModelJson.Turn.class);

        assertThat(turn.hasFinal()).isTrue();
        assertThat(turn.hasAction()).isFalse();
        assertThat(turn.effectiveFinal().answer()).isEqualTo("结论");
    }

    @Test
    void acceptsAToolCallNestedInActionAndNormalizesItsArgumentsIntoAKey() {
        ModelJson.Turn turn = ModelJson.parse(
                "{\"thought\":\"往上游\",\"action\":{\"tool\":\"find_callers\",\"args\":{\"symbol\":\"a.B#m/1\"}}}",
                ModelJson.Turn.class);

        assertThat(turn.hasAction()).isTrue();
        assertThat(turn.toolName()).isEqualTo("find_callers");
        assertThat(turn.argsKey()).isEqualTo("a.B#m/1");
    }
}
