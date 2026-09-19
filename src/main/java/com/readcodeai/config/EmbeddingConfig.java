package com.readcodeai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 向量化客户端的装配：**缺连接配置就降级，绝不阻断启动**（与 LLM / 缓存同一套纪律）。
 *
 * <p>它复用 LLM 的 base-url 与 api-key（同一个供应商、同一把 Key），所以判断条件是
 * "LLM 的连接配置在不在"，而不是自己的一套 —— 配置只有一处，才不会出现"两边配得不一致"。
 */
@Configuration
public class EmbeddingConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConfig.class);

    @Bean
    EmbeddingClient embeddingClient(ReadCodeAiProperties properties) {
        ReadCodeAiProperties.Embedding props = properties.getEmbedding();
        if (!props.isEnabled()) {
            log.warn("向量检索已关闭（readcodeai.embedding.enabled=false）："
                    + "索引与问答照常，只是向量检索与对比实验不可用");
            return new NoopEmbeddingClient("readcodeai.embedding.enabled=false");
        }
        ReadCodeAiProperties.Llm llm = properties.getLlm();
        if (!llm.isEnabled() || llm.getBaseUrl() == null || llm.getBaseUrl().isBlank()
                || llm.getApiKey() == null || llm.getApiKey().isBlank()) {
            log.warn("向量检索与 LLM 共用连接配置，但 LLM 未配置 → 向量检索不可用（"
                    + "索引、确定性问答与全文检索不受影响）");
            return new NoopEmbeddingClient("LLM 未配置（共用 base-url / api-key）");
        }
        EmbeddingClient client = new OpenAiCompatEmbeddingClient(llm, props);
        log.info("向量检索客户端已启用：{}", client.describe());
        return client;
    }
}
