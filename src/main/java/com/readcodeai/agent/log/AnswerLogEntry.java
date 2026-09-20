package com.readcodeai.agent.log;

/**
 * 一条问答流水的全部字段（与 {@code answer_log} 表一一对应）。
 *
 * <p>为什么用一个"字段很多"的扁平结构而不是复用 {@code AskAnswer} / {@code AgentAnswer}：
 * 那两种结果是**给调用方看的**（要带证据、轨迹、核验细节），而流水要的是**能给 SQL 聚合的**。
 * 两者的形状刻意不同 —— 直接把结果对象塞进表里，迟早会为了"统计方便"去改 API 的响应结构。
 *
 * <p>{@code cacheHit} 是关键的一列：命中缓存的那一行**不代表这次花了 token**，
 * 它记的是"这份答案当初生成花了多少、这次省下了"。统计时两笔账分开算（见 {@link AnswerLogStats}）。
 *
 * <p>{@code source} 是另一处关键口径：评估集与对比实验跑题走同一条流水线，
 * 不标出来，"累计问答"就会被它们撑起来（见 {@link AnswerLogSource}）。
 */
public record AnswerLogEntry(
        Long repoId,
        Long questionId,
        String source,
        String question,
        String mode,
        String answeredBy,
        String routeJson,
        int hops,
        int promptTokens,
        int completionTokens,
        int supportPromptTokens,
        int supportCompletionTokens,
        double cost,
        long latencyMs,
        int evidenceVerified,
        int evidenceRejected,
        String supportStatus,
        boolean refused,
        String refusalReason,
        boolean cacheHit,
        String answerJson) {

    /** 这次问答一共动了多少 token（生成 + ③ 层核验）。 */
    public long totalTokens() {
        return (long) promptTokens + completionTokens + supportPromptTokens + supportCompletionTokens;
    }
}
