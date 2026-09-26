package com.readcodeai.agent;

import com.readcodeai.agent.model.AgentAnswer;
import com.readcodeai.agent.model.AgentSeeds;
import com.readcodeai.config.BudgetGuard;

import java.nio.file.Path;

/**
 * 多跳引擎的契约。
 *
 * <p>当前实现只有一个：{@link com.readcodeai.agent.springai.SpringAiAgentLoop}（框架跑工具循环，
 * 四维预算 / 环检测 / 证据收集做在框架的扩展点上）。
 *
 * <p>接口本身保留是有理由的：{@link AgentService} 与所有下游（核验、缓存、记账、界面）
 * 都只认 {@link AgentAnswer}，换引擎不用动它们 —— 这次从手写循环迁到 Spring AI，
 * 以及当年跑 A/B 对照，靠的都是这条缝。
 *
 * <p>手写版曾经是第二个实现（2026-09-26 退役，见 {@code docs/verification-log.md}）：
 * 它那 9 条循环行为用例已逐条搬到 Spring AI 引擎上，代码留在 git 历史里（tag {@code pre-spring-ai}）。
 */
public interface AgentEngine {

    /**
     * @param seeds 确定性路由已经算准的起点（第 0 跳）；它同时决定"哪些引用算有据可依"
     */
    AgentAnswer run(long repoId, Path repoRoot, String question, AgentSeeds seeds, BudgetGuard budget);

    /**
     * 这个引擎当前能不能用。
     *
     * <p><b>为什么可用性要问引擎、不问调用方</b>：引擎依赖的东西未必和调用方一致
     * （Spring AI 版要 {@code spring.ai.openai.*} 的模型 Bean）。调用方自己判断就容易误判 ——
     * 这是写离线覆盖测试时真撞出来的（测试给了模型的桩，却仍被别的检查拦下）。
     */
    boolean available();

    /** 不能用时，一句话说清缺什么（这句话会直接给使用者看）。 */
    String unavailableReason();

    /**
     * 引擎标识 —— **它要进答案缓存的键**：换引擎不能复用旧引擎的答案。
     *
     * <p>默认取实现类名（够稳定、也不会因为改名而撞键）；实现可以覆盖成更短的名字。
     */
    default String id() {
        return getClass().getSimpleName();
    }
}
