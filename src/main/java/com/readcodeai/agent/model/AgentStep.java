package com.readcodeai.agent.model;

import java.util.List;

/**
 * 多跳轨迹里的一跳。**每一跳都带着它自己查到的证据** ——
 * 这是「链路上任何一环都要能回磁盘核对」的落地方式：
 * 结论可以综述，但链路上每一步指向哪几行代码，是查出来的，不是模型回忆出来的。
 *
 * @param hop         第几跳（从 1 开始；0 表示确定性路由给的线索，不是模型跳的）
 * @param thought     模型这一步的思路（可解释性：它为什么查这个）
 * @param tool        工具名
 * @param args        参数原文
 * @param observation 工具返回的观察（截断后）
 * @param evidence    这一跳产生的证据（来自索引与磁盘，不是模型说的）
 * @param subjects    这一跳发现的符号限定名（"上游有哪些方法"这类指标直接用它算）
 * @param repeated    是不是重复调用（被环检测拦下、没有真正执行）
 * @param latencyMs   这一跳的耗时
 */
public record AgentStep(
        int hop,
        String thought,
        String tool,
        String args,
        String observation,
        List<AskEvidence> evidence,
        List<String> subjects,
        boolean repeated,
        long latencyMs) {

    /** 一行式摘要，给日志与人看。 */
    public String digest() {
        return "第 " + hop + " 跳 " + tool + "(" + args + ")"
                + (repeated ? " [重复，已跳过]" : " → " + subjects.size() + " 个结果");
    }
}
