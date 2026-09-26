package com.readcodeai.agent.springai;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * 脚本化假模型（**测试用**）：按提示词内容决定这一轮返回什么 —— 手写测试里
 * {@code ScriptedLlmClient} 的对应物，思路一样：**机制用假模型测**（毫秒级、可断言每个分支、
 * 不联网不花钱），效果才用真模型只报数字。
 *
 * <p>两个坑（都在 spike 里踩过，这里已避开）：
 * <ol>
 *   <li>必须让 {@code getOptions()} 返回**支持工具调用的 options**，否则框架判定"这轮不该执行工具"，
 *       循环会**静默不跑**；</li>
 *   <li>引用它的测试上下文里它要是 {@code @Primary}，否则与自动配置的真模型 Bean 冲突。</li>
 * </ol>
 */
public class ScriptedChatModel implements ChatModel {

    private Function<Prompt, ChatResponse> script = prompt -> text("（脚本未设置）");
    private int promptTokens = 100;
    private int completionTokens = 20;
    /** 每次调用收到的提示词（用来断言"工具结果回灌了没有""历史被压了没有"）。 */
    private final List<Prompt> prompts = new ArrayList<>();

    public ScriptedChatModel script(Function<Prompt, ChatResponse> script) {
        this.script = script;
        return this;
    }

    public ScriptedChatModel withTokenUsage(int promptTokens, int completionTokens) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        return this;
    }

    public List<Prompt> prompts() {
        return prompts;
    }

    /** 被调用了几次。 */
    public int calls() {
        return prompts.size();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        ChatResponse raw = script.apply(prompt);
        return ChatResponse.builder()
                .generations(raw.getResults())
                .metadata(ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(promptTokens, completionTokens))
                        .build())
                .build();
    }

    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    // ---------- 台词构造器 ----------

    /** "我要调用一个工具"。 */
    public static ChatResponse toolCall(String toolName, String jsonArgs) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-" + UUID.randomUUID(), "function", toolName, jsonArgs)))
                .build();
        return new ChatResponse(new ArrayList<>(List.of(new Generation(message))));
    }

    /** "这是我的结论"。 */
    public static ChatResponse text(String content) {
        return new ChatResponse(new ArrayList<>(List.of(new Generation(new AssistantMessage(content)))));
    }
}
