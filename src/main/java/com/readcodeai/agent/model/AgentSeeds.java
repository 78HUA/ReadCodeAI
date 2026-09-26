package com.readcodeai.agent.model;

import java.util.List;

/**
 * **第 0 跳的线索**：确定性路由已经算准的东西（符号 + 它的定义位置），交给多跳引擎当起点。
 *
 * <p>它不是提示词工程，而是分工：符号解析是确定性的活（能算准），让模型从算准的位置起步，
 * 既省一轮预算，也避免它去猜"问题里说的是哪个同名方法"。
 *
 * <p>第二个作用同样重要：{@code evidence} 里那些位置会参与"**引用有没有依据**"的判定 ——
 * 种子把目标符号的定义行交到模型手上，那它引用这个定义就是有据可依，不会被误杀。
 *
 * <p>这个类型原先嵌在手写引擎里；引擎与调用方（{@code AgentService}）都要用它，
 * 所以提到了 model 包 —— 契约不该挂在某一个实现身上。
 */
public record AgentSeeds(List<String> lines, List<AskEvidence> evidence) {

    public static AgentSeeds none() {
        return new AgentSeeds(List.of(), List.of());
    }

    public static AgentSeeds of(List<String> lines, List<AskEvidence> evidence) {
        return new AgentSeeds(lines, evidence);
    }
}
