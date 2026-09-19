package com.readcodeai.config;

import java.util.List;

/**
 * 文本向量化的唯一入口（第 3 层检索的底座）。
 *
 * <p>与 {@link LlmClient} 同一套纪律：接口只留真正需要的方法，未配置时降级为 Noop，
 * 由调用方看 {@link #available()} 决定走不走这条路。
 *
 * <p>它的定位是**检索基线**：第一组对比实验（纯向量 vs 三层检索）靠它出数，
 * 将来若真有"模糊语义查找"的需求，接进问答路由的也是这个接口 —— 但那是判据到了之后的事。
 */
public interface EmbeddingClient {

    /** 是否真的能用。未配置或显式关闭时为 false。 */
    boolean available();

    /** 模型名。**向量按模型隔离**（换模型 = 换向量空间，旧向量全部作废）。 */
    String model();

    /** 向量维度。写入与校验都以它为准，客户端返回的维度必须与之一致。 */
    int dimensions();

    /**
     * 把一批文本变成向量，**顺序与输入一致**。
     *
     * @param inputs 不能为空串（空文本没有语义，检索侧负责过滤）
     */
    EmbeddingBatch embed(List<String> inputs);

    /** 日志与诊断用的一句话。 */
    String describe();

    /**
     * @param promptTokens 这批输入花掉的 token（embed 是要钱的，成本必须能报出来）
     */
    record EmbeddingBatch(List<float[]> vectors, int promptTokens) {
    }
}
