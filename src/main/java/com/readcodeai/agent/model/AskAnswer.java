package com.readcodeai.agent.model;

import java.util.List;

/**
 * 一次问答的结果。
 *
 * <p>{@code answeredBy} 说明答案是怎么来的：{@code STATIC} 的结论**不经过模型**，
 * 是调用图/符号表直接算出来的 —— 这部分即使没配 LLM 也能用。
 *
 * <p>{@code retrievedFrom} 是「这次答案建立在哪些检索结果上」——
 * 答案错了要能分清是**检索没召回**还是**模型组织错了**，没有这个字段就查不了。
 *
 * <p>{@code verification} 是**真读磁盘核验**的结果（第 4 步的分水岭）：
 * 文件存不存在、行号有没有越界、模型引的片段与磁盘内容对不对得上。
 * {@code STATIC} 路线的答案由查询结果直接生成，不需要再做一遍证据核验。
 */
public record AskAnswer(
        String answer,
        List<AskEvidence> evidence,
        boolean refused,
        String refusalReason,
        AnsweredBy answeredBy,
        List<String> retrievedFrom,
        int chunksRetrieved,
        int chunksUsed,
        int promptTokens,
        int completionTokens,
        long latencyMs,
        VerificationSummary verification) {

    public static AskAnswer refused(String reason, List<String> retrievedFrom,
                                    int chunksUsed, long latencyMs) {
        return new AskAnswer(null, List.of(), true, reason, AnsweredBy.NONE,
                retrievedFrom, chunksUsed, chunksUsed, 0, 0, latencyMs, VerificationSummary.none());
    }
}
