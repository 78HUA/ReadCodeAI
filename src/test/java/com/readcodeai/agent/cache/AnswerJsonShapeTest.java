package com.readcodeai.agent.cache;

import com.readcodeai.agent.model.AgentAnswer;
import com.readcodeai.agent.model.AgentMode;
import com.readcodeai.agent.model.AnsweredBy;
import com.readcodeai.agent.model.AskEvidence;
import com.readcodeai.agent.model.StopReason;
import com.readcodeai.agent.model.SupportCheck;
import com.readcodeai.agent.model.VerificationSummary;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缓存里那份答案的 JSON 形状：**加字段不能悄悄把缓存读挂，也不能把"没核验"读成"核验通过"**。
 *
 * <p>为什么专门测这个：答案缓存是把 {@link AgentAnswer} 整个对象序列化进 Redis 的，
 * 而记录类在这个项目里已经咬过两次 —— 一次是记录类的方法不参与序列化（前端显示成空白），
 * 一次是嵌套结构在解析时对不上。缓存这条路更隐蔽：**读挂的表现是"缓存从来不命中"**，
 * 而"缓存不命中"是设计上允许的行为，所以谁也不会去查它。
 *
 * <p>第二个用例守着一条纪律：旧版本写进去的缓存（那时还没有 ③ 层这一项）读出来之后，
 * **绝不能被当成"核验通过"** —— 要么读不出来（缓存按未命中处理），要么这一项是空的。
 */
class AnswerJsonShapeTest {

    /** 与 {@link RedisAnswerCache} 用的是同一个序列化实现（Jackson 3）。 */
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void anAnswerWithSupportCheckSurvivesTheRoundTrip() {
        AgentAnswer original = answer(SupportCheck.unsupported("引的是订单分页，结论说的是菜品分页", 900, 120));

        AgentAnswer back = mapper.readValue(mapper.writeValueAsString(original), AgentAnswer.class);

        assertThat(back.answer()).isEqualTo(original.answer());
        assertThat(back.evidence()).hasSize(1);
        assertThat(back.stopReason()).isEqualTo(StopReason.FINAL);
        assertThat(back.answeredBy()).isEqualTo(AnsweredBy.LLM);
        assertThat(back.verification().verified()).isEqualTo(1);
        assertThat(back.verification().support().status()).isEqualTo(SupportCheck.Status.UNSUPPORTED);
        assertThat(back.verification().support().reason()).contains("菜品分页");
        assertThat(back.verification().support().tokens()).as("判定用量也要能原样取回").isEqualTo(1020);
    }

    @Test
    void aCachedPayloadFromBeforeThisFieldExistedIsNeverReadAsSupported() {
        String json = mapper.writeValueAsString(answer(SupportCheck.notChecked("旧格式")));
        // 模拟"这个字段还不存在时写进去的那份缓存"
        String legacy = json.replaceFirst(",\"support\":\\{[^}]*\\}", "");

        try {
            AgentAnswer back = mapper.readValue(legacy, AgentAnswer.class);
            assertThat(back.verification().support())
                    .as("读得出来也只能是「没有这一项」，绝不能变成核验通过").isNull();
        } catch (RuntimeException e) {
            // 读不出来同样可接受：RedisAnswerCache 会当作未命中（缓存是加速手段，不是正确性依赖）
        }
    }

    private static AgentAnswer answer(SupportCheck support) {
        return new AgentAnswer("结论", List.of(new AskEvidence("A.java", 1, 2, "void a() {}", "为什么引它")),
                false, null, AnsweredBy.LLM, AgentMode.MULTI_HOP, List.of(), 2, 1, 0,
                StopReason.FINAL, 300, 80, 0.0, 1234,
                new VerificationSummary(1, 0, List.of(), support), false, null);
    }
}
