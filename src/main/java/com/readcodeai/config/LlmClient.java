package com.readcodeai.config;

/**
 * LLM 对话补全的唯一入口。
 *
 * <p>刻意只留一个方法：第一版需要的就是「给定提示词、拿回文本」。
 * 多轮与工具调用（第 5 步的多跳）到时候再在这个接口上加，而不是现在就为它设计抽象。
 */
public interface LlmClient {

    /** 是否真的能用。未配置 Key 时为 false，调用方据此走纯静态分析路径（见可降级设计）。 */
    boolean available();

    /** 模型名，用于日志与成本统计。 */
    String model();

    /**
     * 单轮对话补全。
     *
     * <p>返回值带上 token 用量 —— 成本统计与四维预算都要用到它（对应 answer_log 表）。
     */
    Completion complete(String systemPrompt, String userPrompt);

    record Completion(String content, int promptTokens, int completionTokens) {

        public long totalTokens() {
            return (long) promptTokens + completionTokens;
        }
    }
}
