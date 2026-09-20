package com.readcodeai.config;

import java.util.Optional;

/**
 * 多跳循环的**四维预算**：轮次 / 时长 / token / 成本，任一超限即停。
 *
 * <h3>为什么要四个维度而不是一个</h3>
 * 只限轮次挡不住「每轮都塞进超长上下文」；只限 token 挡不住「模型飞快地空转 50 轮」；
 * 时长与成本则是使用者真正在意的那两个数。四个一起看，才叫「框住了」。
 *
 * <p><b>诚实说明它对时长的作用边界</b>：时长预算决定的是**不再发起下一轮**，
 * 而不是中断正在进行的那次 HTTP 调用 —— 单次调用的超时由
 * {@code readcodeai.llm.timeout-seconds} 管。所以实际耗时可能略超这个上限，
 * 报告里要如实写上，不能拿预算值当实测值。
 *
 * <p>同理，成本是**按 token 用量估出来的**（单价由配置给出）。
 * 免费档单价为 0 时这一维恒为 0、闸门不会触发 —— 机制在，但**它管不住 0 成本**，
 * 换成付费档才真正起作用。
 */
public class BudgetGuard {

    public enum Dimension {
        ROUNDS("轮次"),
        DURATION("时长"),
        TOKENS("token"),
        COST("成本");

        private final String label;

        Dimension(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 本次循环实际用掉了多少 —— 成本指标与「平均跳数」都从这里取。 */
    public record Usage(int rounds, int toolCalls, int repeatedCalls, long elapsedMs,
                        long promptTokens, long completionTokens, double estimatedCost) {

        public long totalTokens() {
            return promptTokens + completionTokens;
        }
    }

    private final int maxRounds;
    private final long maxDurationMs;
    private final long maxEstimatedTokens;
    private final double maxEstimatedCost;
    private final double inputPricePerMillion;
    private final double outputPricePerMillion;

    private final long startedAtNanos = System.nanoTime();
    private int rounds;
    private int toolCalls;
    private int repeatedCalls;
    private long promptTokens;
    private long completionTokens;
    private Dimension stoppedBy;

    public BudgetGuard(int maxRounds, long maxDurationMs, long maxEstimatedTokens,
                       double maxEstimatedCost, double inputPricePerMillion,
                       double outputPricePerMillion) {
        this.maxRounds = maxRounds;
        this.maxDurationMs = maxDurationMs;
        this.maxEstimatedTokens = maxEstimatedTokens;
        this.maxEstimatedCost = maxEstimatedCost;
        this.inputPricePerMillion = inputPricePerMillion;
        this.outputPricePerMillion = outputPricePerMillion;
    }

    public static BudgetGuard of(ReadCodeAiProperties properties) {
        ReadCodeAiProperties.Llm llm = properties.getLlm();
        return new BudgetGuard(llm.getMaxRounds(), llm.getMaxDurationMs(),
                llm.getMaxEstimatedTokens(), llm.getMaxEstimatedCost(),
                llm.getInputPricePerMillion(), llm.getOutputPricePerMillion());
    }

    /**
     * 深链模式：**同一套记账，只把额度换大** —— 成本上限沿用主预算（它是使用者真正在意的红线，
     * 不随模式放宽；轮次/时长/token 是可以安全放大的资源额度）。
     *
     * <p>实测依据：第三组对比实验里 **2/3 的题是"轮次用尽"停的**，而轨迹里已经有 60% / 100% 的命中 ——
     * 那些链不是答不了，是没查完。
     */
    public static BudgetGuard deepOf(ReadCodeAiProperties properties) {
        ReadCodeAiProperties.Llm llm = properties.getLlm();
        ReadCodeAiProperties.Llm.Deep deep = llm.getDeep();
        return new BudgetGuard(deep.getMaxRounds(), deep.getMaxDurationMs(),
                deep.getMaxEstimatedTokens(), llm.getMaxEstimatedCost(),
                llm.getInputPricePerMillion(), llm.getOutputPricePerMillion());
    }

    /**
     * 还能不能再走一轮。**任一维度超限即停，并记下是哪一个** ——
     * 报告"为什么停"必须说得出具体维度，否则「预算终止」就成了含糊的说辞。
     */
    public boolean canContinue() {
        if (stoppedBy != null) {
            return false;
        }
        if (rounds >= maxRounds) {
            return stop(Dimension.ROUNDS);
        }
        if (elapsedMillis() >= maxDurationMs) {
            return stop(Dimension.DURATION);
        }
        if (totalTokens() >= maxEstimatedTokens) {
            return stop(Dimension.TOKENS);
        }
        if (estimatedCost() >= maxEstimatedCost) {
            return stop(Dimension.COST);
        }
        return true;
    }

    /** 一次模型调用的用量；**先记后判**：这一轮已经花掉了，必须计入。 */
    public void recordLlmCall(int promptTokens, int completionTokens) {
        this.rounds++;
        this.promptTokens += Math.max(promptTokens, 0);
        this.completionTokens += Math.max(completionTokens, 0);
    }

    /** 一次工具调用的记账。重复调用单独计数 —— 它是「模型在绕圈」的直接证据。 */
    public void recordToolCall(boolean repeated) {
        if (repeated) {
            repeatedCalls++;
        } else {
            toolCalls++;
        }
    }

    public Optional<Dimension> stopReason() {
        return Optional.ofNullable(stoppedBy);
    }

    /**
     * 还剩几轮可查。**要把它告诉模型** —— 实测踩过：模型不知道轮次上限，
     * 就一路查下去直到被闸门掐断，最后一条结论都没给出（多跳变成"查了一半"）。
     * 知道"只剩 1 轮"的模型会自己收尾，这是最便宜的一种约束。
     */
    public int remainingRounds() {
        return Math.max(0, maxRounds - rounds);
    }

    public int maxRounds() {
        return maxRounds;
    }

    public long elapsedMillis() {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    public long totalTokens() {
        return promptTokens + completionTokens;
    }

    public double estimatedCost() {
        return promptTokens / 1_000_000.0 * inputPricePerMillion
                + completionTokens / 1_000_000.0 * outputPricePerMillion;
    }

    public Usage usage() {
        return new Usage(rounds, toolCalls, repeatedCalls, elapsedMillis(),
                promptTokens, completionTokens, estimatedCost());
    }

    public String describeLimits() {
        return "轮次≤" + maxRounds + " · 时长≤" + maxDurationMs + "ms · token≤" + maxEstimatedTokens
                + " · 成本≤" + maxEstimatedCost;
    }

    private boolean stop(Dimension dimension) {
        this.stoppedBy = dimension;
        return false;
    }
}
