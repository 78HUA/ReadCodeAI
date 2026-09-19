package com.readcodeai.verify;

import com.readcodeai.config.EmbeddingClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 假的向量化客户端：**词袋 + hash 分桶 + L2 归一化**，完全确定、零网络。
 *
 * <p>为什么它足以当测试替身：检索数学要验证的是"余弦、top-k 排序、分数语义"，
 * 这些只依赖"**共享词多的文本更相近**"这一条性质 —— hash 词袋恰好满足。
 * 真实 embedding 模型的语义能力是另一回事，那归真实实验（VectorComparisonLiveTest）管，
 * 两者混在一起测，机制bug 会被"模型好坏"淹没。
 *
 * <p>维度刻意用 64（真实模型是 1024）：小维度让"分桶碰撞"更容易暴露排序逻辑的问题。
 */
public class HashEmbeddingClient implements EmbeddingClient {

    private final int dimensions;

    public HashEmbeddingClient(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String model() {
        return "hash-test";
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public EmbeddingBatch embed(List<String> inputs) {
        List<float[]> vectors = new ArrayList<>(inputs.size());
        int tokens = 0;
        for (String text : inputs) {
            vectors.add(vectorize(text));
            tokens += text.length() / 3;
        }
        return new EmbeddingBatch(vectors, tokens);
    }

    @Override
    public String describe() {
        return "hash 词袋（测试替身，" + dimensions + " 维）";
    }

    private float[] vectorize(String text) {
        float[] vector = new float[dimensions];
        for (String token : text.toLowerCase().split("[^a-z0-9\\u4e00-\\u9fff]+")) {
            if (token.isEmpty()) {
                continue;
            }
            vector[Math.floorMod(token.hashCode(), dimensions)] += 1;
        }
        double norm = 0;
        for (float v : vector) {
            norm += (double) v * v;
        }
        if (norm > 0) {
            norm = Math.sqrt(norm);
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) (vector[i] / norm);
            }
        }
        return vector;
    }
}
