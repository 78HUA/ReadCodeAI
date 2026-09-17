package com.readcodeai.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议的对话补全客户端（/chat/completions）。
 *
 * <p>刻意不引任何 AI 框架：需要的只有一个 HTTP 接口，
 * 手写之后第 5 步那个「工具调用循环」是我们自己的代码，讲得清、也改得动。
 */
public class OpenAiCompatClient implements LlmClient {

    private final ReadCodeAiProperties.Llm props;
    private final RestClient restClient;

    public OpenAiCompatClient(ReadCodeAiProperties.Llm props) {
        this.props = props;
        Duration timeout = Duration.ofSeconds(props.getTimeoutSeconds());
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.restClient = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String model() {
        return props.getModel();
    }

    @Override
    public Completion complete(String systemPrompt, String userPrompt) {
        ChatCompletionResponse response = restClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + props.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "model", props.getModel(),
                        "messages", List.of(
                                Map.of("role", "system", "content", systemPrompt),
                                Map.of("role", "user", "content", userPrompt))))
                .retrieve()
                .body(ChatCompletionResponse.class);

        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new IllegalStateException("LLM 返回了空响应");
        }
        Usage usage = response.usage();
        return new Completion(
                response.choices().get(0).message().content(),
                usage == null ? 0 : usage.promptTokens(),
                usage == null ? 0 : usage.completionTokens());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatCompletionResponse(List<Choice> choices, Usage usage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(ChatMessage message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatMessage(String role, String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(
            @JsonProperty("prompt_tokens") Integer promptTokens,
            @JsonProperty("completion_tokens") Integer completionTokens) {
    }
}
