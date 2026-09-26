package com.readcodeai.agent.cache;

import com.readcodeai.agent.model.AgentAnswer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 缓存关掉、或 Redis 连不上时的实现：**永远不命中，也永远不失败**。
 *
 * <p>它与 {@code NoopLlmClient} 是同一个思路但**结果相反**：
 * NoopLlmClient 一被调用就大声失败（因为"模型没说话"和"模型说没有"不能混为一谈）；
 * 而缓存不存在只是"慢一点"，主流程完全不该受影响，所以这里静默放行。
 *
 * <p>区别在哪？模型是**能力**（缺了它功能就没了），缓存是**加速**（缺了它只是慢）。
 */
public class NoopAnswerCache implements AnswerCache {

    private static final Logger log = LoggerFactory.getLogger(NoopAnswerCache.class);

    private final String reason;

    public NoopAnswerCache(String reason) {
        this.reason = reason;
    }

    @Override
    public Optional<AgentAnswer> get(long repoId, String indexedAt, String question, String mode, String model,
                                     String engine) {
        return Optional.empty();
    }

    @Override
    public void put(long repoId, String indexedAt, String question, String mode, String model, String engine,
                    AgentAnswer answer) {
        // 什么都不做是正确行为：缓存不可用时，问答照常跑，只是每次都真算
    }

    @Override
    public String describe() {
        return "未启用（" + reason + "）：问答照常可用，只是每次都会真算一遍";
    }

    @Override
    public boolean enabled() {
        return false;
    }

    public String reason() {
        return reason;
    }
}
