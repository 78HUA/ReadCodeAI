package com.readcodeai.config;

import java.util.List;

/**
 * 向量化不可用时的实现：**不阻断启动，但被真正调用就大声失败**（与 {@link NoopLlmClient} 同一条规则）。
 *
 * <p>区别于缓存的 Noop（静默放行）：向量检索是**能力**而不是加速 ——
 * 静默返回空列表会让上层把"没有向量"当成"没有相关代码"，那是把配置问题伪装成了检索结论。
 */
public class NoopEmbeddingClient implements EmbeddingClient {

    private final String reason;

    public NoopEmbeddingClient(String reason) {
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String model() {
        return "none";
    }

    @Override
    public int dimensions() {
        return 0;
    }

    @Override
    public EmbeddingBatch embed(List<String> inputs) {
        throw new IllegalStateException("向量检索不可用（" + reason
                + "）：符号表 / 调用图 / 全文检索等确定性能力不受影响");
    }

    @Override
    public String describe() {
        return "未启用（" + reason + "）";
    }
}
