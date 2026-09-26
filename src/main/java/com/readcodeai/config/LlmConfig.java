package com.readcodeai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 按配置装配 LLM 客户端。装配过程只记日志、不抛异常 ——
 * 配置缺失不能让整个应用起不来，那是「可降级」这条纪律的底线。
 *
 * <p>实现是 {@link SpringAiLlmClient}（内部走 Spring AI）：模型协议、供应商差异、超时与重试
 * 都交给框架；本项目只保留一个**窄接口** {@link LlmClient}（便于替换、便于用假模型测）。
 *
 * <p><b>为什么还要看 {@code readcodeai.llm.api-key}</b>：{@code application.yml} 给
 * {@code spring.ai.openai.api-key} 留了占位值（否则 Spring AI 的自动配置会因空值拒绝启动，
 * 破坏"缺 Key 也能跑"的纪律），所以"到底有没有真的配 Key"必须在这里判断 ——
 * 状态页与 live 测试门禁读的都是这个 Bean。
 */
@Configuration
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    LlmClient llmClient(ReadCodeAiProperties properties,
                        ObjectProvider<ChatClient.Builder> chatClientBuilder,
                        @Value("${spring.ai.openai.chat.model:}") String springAiModel) {
        ReadCodeAiProperties.Llm llm = properties.getLlm();

        if (!llm.isEnabled()) {
            log.warn("LLM 未启用（readcodeai.llm.enabled=false）：索引、定位、调用关系、全文检索照常可用；"
                    + "自然语言问答与多跳检索不可用");
            return new NoopLlmClient("readcodeai.llm.enabled=false");
        }
        if (!StringUtils.hasText(llm.getApiKey())) {
            log.warn("未配置 readcodeai.llm.api-key：降级为 Noop —— 静态分析能力不受影响，问答与多跳不可用");
            return new NoopLlmClient("未配置 readcodeai.llm.api-key");
        }
        if (!StringUtils.hasText(llm.getBaseUrl())) {
            log.warn("readcodeai.llm.base-url 为空：降级为 Noop（端点会退到默认的 api.openai.com，那不是我们的模型）");
            return new NoopLlmClient("base-url 未配置");
        }
        ChatClient.Builder builder = chatClientBuilder.getIfAvailable();
        if (builder == null) {
            log.warn("Spring AI 没有可用的模型（spring.ai.openai.*）：降级为 Noop");
            return new NoopLlmClient("Spring AI 未装配模型（spring.ai.openai.*）");
        }

        // 模型名以 **Spring AI 的配置**为准：readcodeai.llm.model 与它不一致时，
        // 答案缓存键的 model 维度会失真（缓存按"哪个模型生成的"隔离，写错就白隔离了）。
        String model = StringUtils.hasText(springAiModel) ? springAiModel : llm.getModel();
        log.info("LLM 已启用（Spring AI）：model={}, baseUrl={}", model, llm.getBaseUrl());
        return new SpringAiLlmClient(builder.build(), model);
    }

    /**
     * 把 {@code readcodeai.llm.timeout-seconds} 接到 Spring AI 的 HTTP 客户端上。
     *
     * <p>不接的话它就成了"配了但没用"的死配置 —— 而超时是有意义的：摘要与多跳的单轮调用
     * 慢起来能到几十秒，框架的默认超时太短会把**正常调用**掐掉。
     */
    @Bean
    OpenAiHttpClientBuilderCustomizer llmHttpTimeout(ReadCodeAiProperties properties) {
        return builder -> builder.timeout(Duration.ofSeconds(properties.getLlm().getTimeoutSeconds()));
    }
}
