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
    MULTI_HOP;

    public static AgentMode parse(String text) {
        if (text == null || text.isBlank()) {
            return MULTI_HOP;
        }
        return switch (text.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "single", "single_hop", "single-hop", "单跳" -> SINGLE_HOP;
            case "multi", "multi_hop", "multi-hop", "多跳" -> MULTI_HOP;
            default -> throw new IllegalArgumentException("未知的问答模式：" + text + "（可选 single / multi）");
        };
    }
}
