package com.readcodeai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * {@link LlmClient} 的 **Spring AI 实现**：接口不变，只把"怎么调模型"换成框架。
 *
 * <h3>为什么保留 LlmClient 这个窄接口，而不是让 5 个调用点直接用 ChatClient</h3>
 * 业务与测试依赖的都是这个接口（11 个测试文件用的 {@code ScriptedLlmClient} 也实现它）。
 * 换实现而不换接口，改动面小一个量级，而**降级装配、"模型可用"的状态源**
 * （状态页与 live 测试门禁读的都是它）也自动跟着统一。
 * 框架带来的收益（协议适配、供应商可换，将来的结构化输出 / 流式 / 观测）都在这一层之上做。
 *
 * <p>它只负责**单轮补全**：不给工具、不挂 advisor —— 多跳那条路由
 * {@link com.readcodeai.agent.springai.SpringAiAgentLoop} 负责。
 */
public class SpringAiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(SpringAiLlmClient.class);

    private final ChatClient chatClient;
    private final String model;

    public SpringAiLlmClient(ChatClient chatClient, String model) {
        this.chatClient = chatClient;
        this.model = model;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public Completion complete(String systemPrompt, String userPrompt) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            spec = spec.system(systemPrompt);
        }
        ChatResponse response = spec
                .user(userPrompt == null ? "" : userPrompt)
                .call()
                .chatResponse();

        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new IllegalStateException("LLM 返回了空响应");
        }
        String content = response.getResult().getOutput().getText();
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        int promptTokens = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        int completionTokens = usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        log.debug("单轮补全完成：model={} token in={} out={}", model, promptTokens, completionTokens);
        return new Completion(content == null ? "" : content, promptTokens, completionTokens);
    }
}
