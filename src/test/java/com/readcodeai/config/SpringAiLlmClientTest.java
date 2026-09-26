package com.readcodeai.config;

import com.readcodeai.agent.springai.ScriptedChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1/P2 的离线验收：**`LlmClient` 的实现换成 Spring AI 之后，窄接口的语义一条都没变**
 * （内容、token 用量、模型名、空响应报错），而且 **{@code model()} 取的已经是 Spring AI 的模型名** ——
 * 它要进答案缓存的键，取错就会"缓存按模型隔离"这一维失真。
 */
class SpringAiLlmClientTest {

    @Test
    void 单轮补全返回内容与token用量() {
        ScriptedChatModel model = new ScriptedChatModel()
                .withTokenUsage(1234, 56)
                .script(prompt -> ScriptedChatModel.text("你好，我是模型"));

        LlmClient client = new SpringAiLlmClient(ChatClient.create(model), "glm-4-flash");

        LlmClient.Completion completion = client.complete("系统提示", "用户问题");

        assertThat(completion.content()).isEqualTo("你好，我是模型");
        assertThat(completion.promptTokens()).isEqualTo(1234);
        assertThat(completion.completionTokens()).isEqualTo(56);
        assertThat(completion.totalTokens()).isEqualTo(1290);
    }

    @Test
    void 模型名来自SpringAI的配置而不是另一处配置() {
        ScriptedChatModel model = new ScriptedChatModel();

        // LlmConfig 传进来的就是 spring.ai.openai.chat.model 的值
        LlmClient client = new SpringAiLlmClient(ChatClient.create(model), "spring-ai-config-model");

        assertThat(client.model()).isEqualTo("spring-ai-config-model");
        assertThat(client.available()).as("装得上就是可用（不可用时装配层会换成 Noop）").isTrue();
    }

    @Test
    void 空响应要明确报错而不是返回空字符串() {
        ScriptedChatModel model = new ScriptedChatModel().script(prompt -> null);
        LlmClient client = new SpringAiLlmClient(ChatClient.create(model), "m");

        assertThatThrownBy(() -> client.complete("s", "u"))
                .isInstanceOf(RuntimeException.class);
    }
}
