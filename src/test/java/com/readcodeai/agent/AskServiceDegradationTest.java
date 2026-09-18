package com.readcodeai.agent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 问答接口的降级行为：**没配 LLM 时不是「静默返回空答案」，而是明确报错并说明还剩什么能力**。
 *
 * <p>与 {@code LlmDegradationTest} 的分工：那条测的是客户端装配，这条测的是**接口层的行为**。
 */
@SpringBootTest(properties = {
        "readcodeai.llm.enabled=true",
        "readcodeai.llm.api-key=",
        "readcodeai.llm.base-url=",
        "readcodeai.llm.model="
})
class AskServiceDegradationTest {

    @Autowired
    private AnswerService answerService;

    @Test
    void refusesToAnswerWhenNoLlmIsConfiguredAndSaysWhatStillWorks() {
        assertThatThrownBy(() -> answerService.ask(null, "登录检查在哪做的", null, 5))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("语义问答不可用")
                .hasMessageContaining("确定性能力不受影响");
    }
}
