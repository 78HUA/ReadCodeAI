package com.readcodeai.agent;

import com.readcodeai.agent.model.AgentAnswer;
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
    AgentAnswer run(long repoId, Path repoRoot, String question, AgentLoop.Seeds seeds, BudgetGuard budget);
}
