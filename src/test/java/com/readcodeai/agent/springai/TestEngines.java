package com.readcodeai.agent.springai;

import com.readcodeai.agent.AgentEngine;
import com.readcodeai.agent.ToolRegistry;
import com.readcodeai.agent.model.AgentAnswer;
import com.readcodeai.agent.model.AgentSeeds;
import com.readcodeai.config.BudgetGuard;
import com.readcodeai.config.ReadCodeAiProperties;
import com.readcodeai.evidence.EvidenceVerifier;
import com.readcodeai.evidence.SupportChecker;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;

/**
 * **给"自己 new AgentService"的服务层用例用**：手工装配一个跑在脚本模型上的多跳引擎。
 *
 * <p>为什么要它：服务层用例（缓存键、流水记账、③ 层拒答、深链额度……）自己 new
 * {@code AgentService}，需要把一个引擎当参数传进去。手写引擎退役后，能用的引擎就是
 * {@link SpringAiAgentLoop}，而它要 {@code ObjectProvider<ChatClient.Builder>} —— 本类把这个
 * 装配细节收在一处，别再让每个用例各写一遍。
 *
 * <p>另外给两种"不需要真引擎"的场合备了替身：{@link #unavailable}（要验"没配模型时的降级"）
 * 与 {@link #unused}（这些用例走单跳/确定性路线，引擎**不该被调用到**；调到了就直接失败，
 * 比静默返回一个空答案好）。
 */
public final class TestEngines {

    private TestEngines() {
    }

    /** 真引擎 + 假模型：脚本怎么写见 {@link ScriptedChatModel#scriptLines}。 */
    public static SpringAiAgentLoop on(ChatModel model, ToolRegistry toolRegistry,
                                       EvidenceVerifier evidenceVerifier, SupportChecker supportChecker,
                                       ReadCodeAiProperties properties) {
        return new SpringAiAgentLoop(provider(model), toolRegistry, evidenceVerifier,
                supportChecker, properties);
    }

    /** "模型没配"的引擎：可用性为假，调用方应当据此报"多跳不可用"而不是崩掉。 */
    public static AgentEngine unavailable(String reason) {
        return new AgentEngine() {
            @Override
            public AgentAnswer run(long repoId, java.nio.file.Path repoRoot, String question,
                                   AgentSeeds seeds, BudgetGuard budget) {
                throw new IllegalStateException("引擎不可用，不该被调用：" + reason);
            }

            @Override
            public boolean available() {
                return false;
            }

            @Override
            public String unavailableReason() {
                return reason;
            }

            @Override
            public String id() {
                return "test-unavailable";
            }
        };
    }

    /** "用不到"的引擎：这些用例不该走到多跳，走到了就直接失败（让断言错位暴露出来）。 */
    public static AgentEngine unused() {
        return new AgentEngine() {
            @Override
            public AgentAnswer run(long repoId, java.nio.file.Path repoRoot, String question,
                                   AgentSeeds seeds, BudgetGuard budget) {
                throw new AssertionError("这条用例不该走到多跳引擎（它只走单跳 / 确定性路线）");
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String unavailableReason() {
                return "";
            }

            @Override
            public String id() {
                return "test-unused";
            }
        };
    }

    /**
     * 只支持 {@code getIfAvailable()} 的最小 {@code ObjectProvider}（引擎只需要这一个方法）。
     *
     * <p>⚠️ **每次取都要给一个新的 Builder**：容器里那个 {@code ChatClient.Builder} 是 prototype 作用域，
     * 引擎每跑一次问答就取一个；而 Builder 是**可变**的（{@code defaultAdvisors} 是往上加），
     * 复用同一个实例会让第二次 run 叠出第二个工具循环 advisor ——
     * 框架会直接抛 {@code At most one ToolAdvisor is allowed}。这里如实模拟 prototype 的语义。
     */
    static ObjectProvider<ChatClient.Builder> provider(ChatModel model) {
        return new ObjectProvider<>() {
            @Override
            public ChatClient.Builder getObject() {
                return ChatClient.builder(model);
            }

            @Override
            public ChatClient.Builder getObject(Object... args) {
                return ChatClient.builder(model);
            }

            @Override
            public ChatClient.Builder getIfAvailable() {
                return ChatClient.builder(model);
            }

            @Override
            public ChatClient.Builder getIfUnique() {
                return ChatClient.builder(model);
            }
        };
    }
}
