package com.readcodeai.agent;

import com.readcodeai.agent.model.AgentAnswer;
import com.readcodeai.agent.model.AgentSeeds;
import com.readcodeai.config.BudgetGuard;

import java.nio.file.Path;

/**
 * 多跳引擎的契约。
 *
 * <p>两个实现：手写的 {@link AgentLoop}（默认）与 Spring AI 版
 * {@link com.readcodeai.agent.springai.SpringAiAgentLoop}（实验，配置 `readcodeai.agent.engine=spring-ai` 启用）。
 *
 * <p>有了这个接口，{@link AgentService} 能按配置切换，两个引擎也能跑同一批题做 A/B ——
 * 而所有下游（核验、缓存、记账、界面）一行都不用改：它们只认 {@link AgentAnswer}。
 */
public interface AgentEngine {

    /**
     * @param seeds 确定性路由已经算准的起点（第 0 跳）；它同时决定"哪些引用算有据可依"
     */
    AgentAnswer run(long repoId, Path repoRoot, String question, AgentSeeds seeds, BudgetGuard budget);

    /**
     * 这个引擎当前能不能用。
     *
     * <p><b>为什么可用性要问引擎、不问调用方</b>：两个引擎依赖的东西不一样
     * （手写版要 {@code readcodeai.llm.*} 那个客户端，Spring AI 版要 {@code spring.ai.openai.*}
     * 的模型 Bean）。调用方自己判断就容易把"另一个引擎能用"误判成"都不能用"——
     * 这是写离线覆盖测试时真撞出来的（测试给了模型的桩，却仍被手写客户端的检查拦下）。
     */
    boolean available();

    /** 不能用时，一句话说清缺什么（这句话会直接给使用者看）。 */
    String unavailableReason();
}
