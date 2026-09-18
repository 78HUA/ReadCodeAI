package com.readcodeai.agent.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一次 Agent 问答的完整结果：结论 + 证据 + **轨迹**。
 *
 * <p>单跳结果也能装进来（{@link #from(AskAnswer)}），这样两种模式可以放在同一张表里比较 ——
 * 第三组对比实验（单跳 vs 多跳）要的就是"同一批问题、两种跑法、同一套指标"。
 *
 * @param evidence   结论的证据。**只放通过磁盘核验的**；拒答时放轨迹里查到的证据（见 {@link #trailEvidence}）
 * @param steps      多跳轨迹。单跳与确定性路线为空
 * @param stopReason 为什么停 —— 没有它，「没答出来」就没法诊断
 */
public record AgentAnswer(
        String answer,
        List<AskEvidence> evidence,
        boolean refused,
        String reason,
        AnsweredBy answeredBy,
        AgentMode mode,
        List<AgentStep> steps,
        int rounds,
        int toolCalls,
        int repeatedCalls,
        StopReason stopReason,
        int promptTokens,
        int completionTokens,
        double estimatedCost,
        long latencyMs,
        VerificationSummary verification) {

    /** 单跳 / 确定性路线的结果转换 —— 两种模式因此可以共用一套指标。 */
    public static AgentAnswer from(AskAnswer answer, AgentMode mode, StopReason stopReason) {
        return new AgentAnswer(answer.answer(), answer.evidence(), answer.refused(),
                answer.refusalReason(), answer.answeredBy(), mode, List.of(), 0, 0, 0,
                stopReason, answer.promptTokens(), answer.completionTokens(), 0,
                answer.latencyMs(), answer.verification());
    }

    /** 轨迹里查到的全部证据（与结论引用的是两回事：轨迹证据是查出来的，未经模型复述）。 */
    public List<AskEvidence> trailEvidence() {
        List<AskEvidence> all = new ArrayList<>();
        for (AgentStep step : steps) {
            all.addAll(step.evidence());
        }
        return all;
    }

    /**
     * 结论证据 ∪ 轨迹证据 —— **对比实验的计量口径**。
     *
     * <p>为什么不是只算结论引用的那几条：多跳的价值在于"把链路查出来"，
     * 而链路是工具一跳一跳查到的（每条都回索引/磁盘核对过），模型最后只是把它讲出来。
     * 只算引用会低估轨迹的实际产出，只算轨迹又会忽略模型的组织。两个都算，并在报告里写明口径。
     */
    public List<AskEvidence> allEvidence() {
        Set<String> seen = new LinkedHashSet<>();
        List<AskEvidence> all = new ArrayList<>();
        for (AskEvidence evidence : evidence) {
            if (seen.add(evidence.location() + "|" + evidence.snippet())) {
                all.add(evidence);
            }
        }
        for (AskEvidence evidence : trailEvidence()) {
            if (seen.add(evidence.location() + "|" + evidence.snippet())) {
                all.add(evidence);
            }
        }
        return all;
    }

    /** 轨迹里发现过的符号限定名（去重）。 */
    public Set<String> subjectsFound() {
        Set<String> subjects = new LinkedHashSet<>();
        for (AgentStep step : steps) {
            subjects.addAll(step.subjects());
        }
        return subjects;
    }

    /** 轨迹的一行行摘要，给日志与界面用。 */
    public List<String> trailDigest() {
        return steps.stream().map(AgentStep::digest).toList();
    }

    public boolean budgetStopped() {
        return stopReason != null && stopReason.isBudget();
    }
}
