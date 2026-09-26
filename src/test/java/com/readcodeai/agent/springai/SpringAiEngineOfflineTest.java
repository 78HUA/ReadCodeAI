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

    /**
     * 脚本化假模型（Spring AI 版）：**按提示词内容决定这一轮返回什么**。
     *
     * <p>它是手写测试里 {@code ScriptedLlmClient} 的对应物，思路完全一样：
     * 机制用假模型测（毫秒级、可断言每个分支），效果才用真模型只报数字。
     *
     * <p>两个坑（都在 spike 里踩过，这里已避开）：假模型必须让 {@code getOptions()}
     * 返回 <b>支持工具调用的 options</b>，否则框架判定"这轮不该执行工具"，循环会**静默不跑**；
     * 另外它得是 {@code @Primary}，否则与自动配置的真模型 Bean 冲突。
     */
    static class ScriptedChatModel implements ChatModel {

        private java.util.function.Function<Prompt, ChatResponse> script = prompt -> text("（脚本未设置）");

        void script(java.util.function.Function<Prompt, ChatResponse> script) {
            this.script = script;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            ChatResponse raw = script.apply(prompt);
            return ChatResponse.builder()
                    .generations(raw.getResults())
                    .metadata(ChatResponseMetadata.builder()
                            .usage(new DefaultUsage(100, 20))
                            .build())
                    .build();
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        /** "我要调用一个工具"。 */
        static ChatResponse toolCall(String toolName, String jsonArgs) {
            AssistantMessage message = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            "call-" + UUID.randomUUID(), "function", toolName, jsonArgs)))
                    .build();
            return new ChatResponse(new ArrayList<>(List.of(new Generation(message))));
        }

        /** "这是我的结论"。 */
        static ChatResponse text(String content) {
            return new ChatResponse(new ArrayList<>(List.of(new Generation(new AssistantMessage(content)))));
        }
    }

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
