package com.readcodeai.agent.tools;

import com.readcodeai.agent.model.AskEvidence;

import java.util.List;

/**
 * 一次工具调用的结果。
 *
 * <p>三种东西是刻意分开的，别混：
 * <ul>
 *   <li>{@code observation} —— 给**模型**看的文本（会进提示词，所以有长度上限）</li>
 *   <li>{@code evidence} —— 给**核验器**看的证据（文件 + 行号 [+ 片段]），来自索引与磁盘，不是模型说的</li>
 *   <li>{@code subjects} —— 给**指标**看的符号限定名（"链路上游有哪些方法"直接用它算集合）</li>
 * </ul>
 * 混在一起就会出现"模型复述一遍就变成证据"这种最不该发生的事。
 */
public record ToolResult(String observation, List<AskEvidence> evidence, List<String> subjects) {

    /** 没查到（或参数不对）。**不是错误**：模型据此换个查法，是循环的正常一步。 */
    public static ToolResult miss(String observation) {
        return new ToolResult(observation, List.of(), List.of());
    }

    public static ToolResult of(String observation, List<AskEvidence> evidence, List<String> subjects) {
        return new ToolResult(observation, evidence, subjects);
    }

    public boolean found() {
        return !subjects.isEmpty() || !evidence.isEmpty();
    }
}
