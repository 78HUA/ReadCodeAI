package com.readcodeai.agent.log;

import java.util.List;

/**
 * 问答流水的累计统计 —— 「④ 指标」页里「运行统计」那一块的数据。
 *
 * <h3>口径（每一条都要跟着数字一起显示，否则这些数字会骗人）</h3>
 * <ol>
 *   <li><b>只算用户提问</b>（{@code source=USER}）：评估集与对比实验跑题走同一条流水线，
 *       跑一次评估就是 200+ 行。它们单独记在 {@code evalQuestions} 里 ——
 *       "这个服务被用了多少次"与"我们给自己跑了多少题"是两件事。</li>
 *   <li><b>token / 成本不含缓存命中行</b>：命中缓存的那次没有真的再花一遍
 *       （{@code savedTokens} 单独给出"省下了多少"）。把两者混在一起，累计值会随重复提问虚涨。</li>
 *   <li><b>{@code supportTokens} 是 ③ 层核验的用量</b>，与生成答案是两笔账；金额里已经含它。</li>
 *   <li><b>{@code evidenceRejected} 是首轮未通过的条数</b>（含被定向修正救回的），
 *       不等于"最终答案里混了几条假证据"—— 返回的证据永远只由通过核验的那些组成。</li>
 * </ol>
 *
 * <p>统计范围就是**库里现存的流水**（删仓库会级联删掉它的问答记录），页面要把这句话写在旁边。
 */
public record AnswerLogStats(
        long userQuestions,
        long evalQuestions,
        long refusals,
        long cacheHits,
        long promptTokens,
        long completionTokens,
        long supportTokens,
        double cost,
        long savedTokens,
        double savedCost,
        long avgLatencyMs,
        long maxLatencyMs,
        long evidenceVerified,
        long evidenceRejected,
        List<ModeRow> byMode) {

    /** 按路线分的一行（静态 / 单跳 / 多跳各被问了多少次）。 */
    public record ModeRow(String mode, long count, long refusals, long avgLatencyMs) {
    }

    public static AnswerLogStats empty() {
        return new AnswerLogStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of());
    }

    /** 拒答率 —— 分母是用户提问数，所以它衡量的是"问多少句答不上来"。 */
    public double refusalRate() {
        return userQuestions == 0 ? 0 : (double) refusals / userQuestions;
    }

    public long totalTokens() {
        return promptTokens + completionTokens + supportTokens;
    }
}
