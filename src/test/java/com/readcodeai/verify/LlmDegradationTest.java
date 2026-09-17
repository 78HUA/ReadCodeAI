package com.readcodeai.verify;

import com.readcodeai.config.LlmClient;
import com.readcodeai.config.NoopLlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 第 0 步验证 3：没配 LLM Key 时必须能正常启动。
 *
 * <p>用内联属性把三样配置显式清空，让结果不受本机环境变量影响 ——
 * 否则哪天机器上配了 Key，这个测试会莫名其妙地开始失败。
 */
@SpringBootTest(properties = {
        "readcodeai.llm.enabled=true",
        "readcodeai.llm.api-key=",
        "readcodeai.llm.base-url=",
        "readcodeai.llm.model="
})
class LlmDegradationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private LlmClient llmClient;

    @Test
    void startsUpWithNoLlmConfigurationAtAll() {
        assertThat(context).isNotNull();
        assertThat(context.containsBean("llmClient")).isTrue();
    }

    @Test
    void degradesToTheNoopClient() {
        assertThat(llmClient)
                .as("没配 Key 时应降级为 Noop，而不是让启动失败")
                .isInstanceOf(NoopLlmClient.class);
        assertThat(llmClient.available()).isFalse();
    }

    @Test
    void refusesToSilentlyReturnAnEmptyAnswer() {
        assertThatThrownBy(() -> llmClient.complete("system", "user"))
                .as("被真正调用时要大声失败 —— 静默返回空文本会让「没答案」和「模型说没有」混淆")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api-key");
    }
}
