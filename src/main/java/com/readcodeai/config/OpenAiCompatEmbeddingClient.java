package com.readcodeai.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议的向量化客户端（/embeddings）：手写 HTTP，**不引 AI SDK**。
 *
 * <p>为什么对话那条线已经换成 Spring AI（见 {@link SpringAiLlmClient}），这条还留着手写：
 * 向量只服务**评估基线**与状态页、没接入问答路由（换过去收益太小），换不换都不影响主链路。
 *
 * <p>请求/响应形状是 2026-09-20 用 curl 实测钉住的（见 verification-log）：
 * 请求 {@code {"model","dimensions","input":[...]}}，批量可用；
 * 响应 {@code {data:[{index,embedding:[...]}], usage:{prompt_tokens,...}}}，
 * {@code data} 里带 {@code index}，**按它排序再取**而不是信任返回顺序 —— 零成本的保险。
 */
public class OpenAiCompatEmbeddingClient implements EmbeddingClient {

    private final ReadCodeAiProperties.Llm llm;
    private final ReadCodeAiProperties.Embedding props;
    private final RestClient restClient;

    public OpenAiCompatEmbeddingClient(ReadCodeAiProperties.Llm llm, ReadCodeAiProperties.Embedding props) {
        this.llm = llm;
        this.props = props;
        Duration timeout = Duration.ofSeconds(llm.getTimeoutSeconds());
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.restClient = RestClient.builder()
                .baseUrl(llm.getBaseUrl())
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
    public int dimensions() {
        return props.getDimensions();
    }

    @Override
    public EmbeddingBatch embed(List<String> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return new EmbeddingBatch(List.of(), 0);
        }
        EmbeddingResponse response = restClient.post()
                .uri("/embeddings")
                .header("Authorization", "Bearer " + llm.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "model", props.getModel(),
                        "dimensions", props.getDimensions(),
                        "input", inputs))
                .retrieve()
                .body(EmbeddingResponse.class);

        if (response == null || response.data() == null || response.data().size() != inputs.size()) {
            throw new IllegalStateException("embedding 返回的条数与输入不一致（期望 "
                    + inputs.size() + "）");
        }
        List<Item> ordered = new ArrayList<>(response.data());
        ordered.sort(Comparator.comparingInt(Item::index));
        if (ordered.get(0).index() != 0 || ordered.get(ordered.size() - 1).index() != inputs.size() - 1) {
            throw new IllegalStateException("embedding 返回的 index 不连续，无法与输入对齐");
        }
        List<float[]> vectors = new ArrayList<>(ordered.size());
        for (Item item : ordered) {
            if (item.embedding() == null || item.embedding().size() != props.getDimensions()) {
                throw new IllegalStateException("embedding 维度不符（期望 " + props.getDimensions()
                        + "，实际 " + (item.embedding() == null ? 0 : item.embedding().size()) + "）");
            }
            float[] vector = new float[item.embedding().size()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = item.embedding().get(i).floatValue();
            }
            vectors.add(vector);
        }
        return new EmbeddingBatch(List.copyOf(vectors),
                response.usage() == null || response.usage().promptTokens() == null
                        ? 0 : response.usage().promptTokens());
    }

    @Override
    public String describe() {
        return props.getModel() + "（" + props.getDimensions() + " 维，与 LLM 共用连接配置）";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingResponse(List<Item> data, Usage usage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Item(int index, List<Double> embedding) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(@JsonProperty("prompt_tokens") Integer promptTokens) {
    }
}
