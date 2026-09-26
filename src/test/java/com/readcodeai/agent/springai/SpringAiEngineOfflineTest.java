package com.readcodeai.agent.springai;

import com.readcodeai.agent.AgentService;
import com.readcodeai.agent.model.AgentAnswer;
import com.readcodeai.agent.model.AgentMode;
import com.readcodeai.agent.model.AskEvidence;
import com.readcodeai.agent.model.StopReason;
import com.readcodeai.retrieve.SymbolQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **默认引擎（Spring AI）的离线覆盖**：用脚本化假模型把整条链路跑完整，
 * 不联网、不花钱、结果确定。
 *
 * <p>为什么必须补这一条：切换默认引擎之后，原有的 11 个假模型用例走的都是
 * **手写引擎**（它们注入 {@code ScriptedLlmClient}，只能配手写引擎）。
 * 不补这条，CI 会一直"绿着一条已经不当默认的路" —— 那比没有 CI 更糟。
 *
 * <p>它验三件事：
 * <ol>
 *   <li>框架真的会跑工具循环（轨迹非空、工具真的被调用）；</li>
 *   <li>**证据留得住**：工具侧收集 → 结论引用它 → 过磁盘核验（这是最小迁移的关键机制）；</li>
 *   <li>四维预算在框架循环里也生效：额度用尽即止，且说得出为什么停。</li>
 * </ol>
 */
@SpringBootTest
class SpringAiEngineOfflineTest {

    /** 提示词或工具结果里的第一个「文件:行号」——脚本拿它当结论的证据（真实位置，核验才过得去）。 */
    private static final Pattern LOCATION = Pattern.compile("([\\w/.-]+\\.java):(\\d+)");

    @Autowired
    private AgentService agentService;

    @Autowired
    private SymbolQueryService queries;

    @Autowired
    private ScriptedChatModel model;

    /** 桩在 Spring 上下文里是单例：每条用例前重置，否则调用计数会跨用例累计。 */
    @org.junit.jupiter.api.BeforeEach
    void resetStub() {
        model.reset();
    }

    @Test
    void 默认引擎在离线假模型下也能跑完工具循环并把证据留住() {
        java.util.concurrent.atomic.AtomicInteger turn = new java.util.concurrent.atomic.AtomicInteger();
        model.script(prompt -> {
            if (turn.incrementAndGet() == 1) {
                // 第一轮：查"谁调用了它"（走真工具、真索引）
                return ScriptedChatModel.toolCall("findCallers", "{\"symbol\":\"AgentService.ask\"}");
            }
            // 第二轮：引用**工具查出来的**位置给结论（取最后一个匹配 = 工具结果里的那个）
            Matcher location = LOCATION.matcher(textOf(prompt));
            String file = null;
            int line = 0;
            while (location.find()) {
                file = location.group(1);
                line = Integer.parseInt(location.group(2));
            }
            assertThat(file).as("提示词里应当带着工具查出来的「文件:行号」").isNotNull();
            return ScriptedChatModel.text(finalJson(file, line));
        });

        long repoId = queries.requireLatestRepoId();
        AgentAnswer answer = agentService.ask(repoId, "AgentService.ask 的 question 参数是从哪里传进来的？",
                AgentMode.MULTI_HOP, null, null, false);

        assertThat(answer.steps()).as("框架真的跑了工具循环").isNotEmpty();
        assertThat(answer.toolCalls()).as("至少跳一次").isGreaterThanOrEqualTo(1);
        assertThat(answer.refused()).as("不该拒答：" + answer.reason()).isFalse();
        assertThat(answer.evidence()).as("**证据留住了**（工具侧收集 + 磁盘核验通过）").isNotEmpty();
        assertThat(answer.evidence()).allSatisfy(e -> assertThat(e.file()).endsWith(".java"));
        assertThat(answer.stopReason()).isEqualTo(StopReason.FINAL);
    }

    @Test
    void 额度用尽时框架循环会被中断并说得出原因() {
        model.script(prompt -> ScriptedChatModel.toolCall("findCallers", "{\"symbol\":\"AgentService.ask\"}"));

        long repoId = queries.requireLatestRepoId();
        // 必须用**链式问法**：确定性问题（"谁调用了 X"）根本不会走到引擎（能算准的别猜）
        AgentAnswer answer = agentService.ask(repoId, "AgentService.ask 的 question 参数是从哪里传进来的？",
                AgentMode.MULTI_HOP, null, null, false);

        assertThat(answer.refused()).as("被闸门拦下时不给结论").isTrue();
        assertThat(answer.stopReason()).as("要能说出停在哪一维").isEqualTo(StopReason.BUDGET_ROUNDS);
        assertThat(answer.reason()).as("原因里要说清'没给出结论'").contains("没有给出结论");
        assertThat(answer.steps()).as("轨迹里查到的东西照样交出来").isNotEmpty();
    }

    /**
     * P4：**A-B 压缩真的在起作用** —— 三跳 + 保留近两跳 ⇒ 恰好一条被压成一行事实，
     * 而且压缩之后模型照样能收尾（循环没被 advisor 弄坏）。
     */
    @Test
    void 更早的跳会被压成一行事实而近两跳保留原文() {
        java.util.concurrent.atomic.AtomicInteger turn = new java.util.concurrent.atomic.AtomicInteger();
        model.script(prompt -> {
            int t = turn.incrementAndGet();
            if (t <= 3) {
                // 三个**不同**的查询：都真执行（参数不同，环检测不会拦）
                return ScriptedChatModel.toolCall("textSearch", "{\"query\":\"class T" + t + "\"}");
            }
            Matcher location = LOCATION.matcher(textOf(prompt));
            String file = null;
            int line = 0;
            while (location.find()) {
                file = location.group(1);
                line = Integer.parseInt(location.group(2));
            }
            assertThat(file).as("三跳之后提示词里应当仍带着位置信息").isNotNull();
            return ScriptedChatModel.text(finalJson(file, line));
        });

        AgentAnswer answer = agentService.ask(queries.requireLatestRepoId(),
                "AgentService.ask 的 question 参数是从哪里传进来的？", AgentMode.MULTI_HOP, null, null, false);

        assertThat(answer.refused()).as("压缩之后照样能收尾：" + answer.reason()).isFalse();
        assertThat(model.calls()).isEqualTo(4);          // 3 跳 + 1 次收尾

        String lastPrompt = textOf(model.prompts().get(model.calls() - 1));
        long compacted = lastPrompt.lines().filter(l -> l.contains("[已压缩]")).count();
        assertThat(compacted)
                .as("三跳 + 保留近两跳（readcodeai.llm.keep-full-observations=2）⇒ 恰好 1 条被压缩")
                .isEqualTo(1);
    }

    /** 一条合法的结论 JSON：不写 snippet（只核验文件与行号）。 */
    private static String finalJson(String file, int line) {
        return """
                {"final":{"answer":"由 %s 第 %d 行给出。","evidence":[{"file":"%s","startLine":%d,"endLine":%d,\
                "snippet":"","why":"工具查出来的位置"}],"refused":false,"refusalReason":""}}"""
                .formatted(file, line, file, line, line);
    }

    /** 把提示词里的文本（含工具结果）拼出来，供脚本"看着已经查到的东西"决定下一步。 */
    private static String textOf(Prompt prompt) {
        StringBuilder sb = new StringBuilder();
        for (var message : prompt.getInstructions()) {
            sb.append(message.getText()).append('\n');
            if (message instanceof org.springframework.ai.chat.messages.ToolResponseMessage tool) {
                for (var response : tool.getResponses()) {
                    sb.append(response.name()).append(' ').append(response.responseData()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 测试替身

    /** 假模型桩已抽成共享类 {@link ScriptedChatModel}（客户端与引擎的离线测试共用）。 */
    @TestConfiguration
    static class StubModelConfig {

        /** @Primary：让 ChatClient 用这个桩，而不是自动配置里那个真模型。 */
        @Bean
        @Primary
        ScriptedChatModel scriptedChatModel() {
            return new ScriptedChatModel();
        }
    }
}
