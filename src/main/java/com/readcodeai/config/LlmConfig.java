package com.readcodeai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 按配置装配 LLM 客户端。装配过程只记日志、不抛异常 ——
 * 配置缺失不能让整个应用起不来，那是「可降级」这条纪律的底线。
 */
@Configuration
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    LlmClient llmClient(ReadCodeAiProperties properties) {
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
        if (!StringUtils.hasText(llm.getBaseUrl()) || !StringUtils.hasText(llm.getModel())) {
            log.warn("readcodeai.llm.base-url 或 readcodeai.llm.model 为空：降级为 Noop");
            return new NoopLlmClient("base-url 或 model 未配置");
        }

        log.info("LLM 已启用：model={}, baseUrl={}", llm.getModel(), llm.getBaseUrl());
        return new OpenAiCompatClient(llm);
    }
}
