package com.readcodeai.agent.model;

/**
 * 问答走哪条路。
 *
 * <p>{@code SINGLE_HOP} 与 {@code MULTI_HOP} 是**同一个问题的两种问法**，
 * 也是第三组对比实验的两个对照组：单跳一次检索就作答，多跳由模型决定跳向与何时停。
 */
public enum AgentMode {
    /** 单次检索 → 由模型组织答案（或走确定性查询，不经模型） */
    SINGLE_HOP,
    /** 模型自主多跳：自己决定查谁、跳几跳、什么时候停 */
    MULTI_HOP,
    /**
     * 总结类问题：**自动路由**到结构化摘要 —— 结构由索引算出、语义由模型补，不经过检索与多跳。
     *
     * <p>它不是用户选的模式，而是问题命中"总结意图"后的结果（见 {@code SummaryIntent}）。
     * 实测依据：同一个「这个项目是干什么的」，走多跳 93 秒没给出结论，走摘要 15 秒给出一份可核对的答案。
     */
    SUMMARY;

    public static AgentMode parse(String text) {
        if (text == null || text.isBlank()) {
            return MULTI_HOP;
        }
        return switch (text.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "single", "single_hop", "single-hop", "单跳" -> SINGLE_HOP;
            case "multi", "multi_hop", "multi-hop", "多跳" -> MULTI_HOP;
            case "summary", "摘要" -> SUMMARY;
            default -> throw new IllegalArgumentException("未知的问答模式：" + text + "（可选 single / multi / summary）");
        };
    }
}
