package com.readcodeai.agent.springai;

import com.readcodeai.config.BudgetGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.DefaultToolExecutionResult;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * 四维预算的落点：**接管框架的工具循环执行器**。
 *
 * <p>怎么接上去的：Spring AI 的自动配置给 {@code ToolCallingManager} 标了
 * {@code @ConditionalOnMissingBean}，所以把它注册成 Bean 就覆盖了默认实现。
 *
 * <h3>每一轮做三件事</h3>
 * <ol>
 *   <li><b>记账</b>：从 {@code ChatResponse} 的 usage 取本轮 token → {@link BudgetGuard#recordLlmCall}
 *       （预算的四个维度全部由主项目现成的 {@link BudgetGuard} 管，逻辑一行没重写）；</li>
 *   <li><b>留痕</b>：把模型调工具前顺带输出的话记下来（原生 tool calling 协议里没有 thought 字段，
 *       这是最接近"推理"的东西）；</li>
 *   <li><b>闸门</b>：{@code canContinue()==false} → 抛框架的
 *       {@link ToolCallLimitExceededException} 中断循环（它只表达"调用次数超限"，
 *       **真正的原因由本类自己记在 {@link #stopDetail()} 里**）。</li>
 * </ol>
 */
public class BudgetToolCallingManager implements ToolCallingManager {

    private static final Logger log = LoggerFactory.getLogger(BudgetToolCallingManager.class);

    private final ToolCallingManager delegate;
    private final BudgetGuard budget;
    private final SpringAiLoopState state;

    /** 为什么停：四维里的哪一维、额度多少 —— 框架的异常表达不了，所以自己记。 */
    private String stopDetail;
    /** 停的原因是不是"最后一轮还在调工具"（与手写版同一条规则：最后一轮留给结论）。 */
    private boolean lastRoundStillCallingTools;
    private int rounds;

    public BudgetToolCallingManager(BudgetGuard budget, SpringAiLoopState state) {
        this.delegate = DefaultToolCallingManager.builder().build();
        this.budget = budget;
        this.state = state;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        rounds++;
        var usage = chatResponse.getMetadata() == null ? null : chatResponse.getMetadata().getUsage();
        int in = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        int out = usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        budget.recordLlmCall(in, out);
        state.noteAssistantText(assistantText(chatResponse));
        log.info("[SpringAI] 第 {} 轮：token in={} out={} · 累计 {}/{} token · 还剩 {} 轮",
                rounds, in, out, budget.totalTokens(), budget.maxRounds(), budget.remainingRounds());

        if (!budget.canContinue()) {
            stopDetail = describeStop();
            log.warn("[SpringAI] 触发预算闸门，中断循环：{}", stopDetail);
            throw limitExceeded(prompt);
        }
        // 与手写版同一条规则：**最后一轮留给结论**。实测模型会一直查到被掐断、一条结论都不给，
        // 那样多跳就只剩"查了一半" —— 这时宁可停下并交出轨迹。
        if (budget.remainingRounds() <= 1) {
            lastRoundStillCallingTools = true;
            stopDetail = "最后一轮模型仍然在调用工具（" + toolNameOf(chatResponse) + "），没有给出结论，"
                    + "已查到的轨迹一并交出";
            log.warn("[SpringAI] 最后一轮仍在调工具，按轮次预算停：{}", stopDetail);
            throw limitExceeded(prompt);
        }
        return delegate.executeToolCalls(prompt, chatResponse);
    }

    private ToolCallLimitExceededException limitExceeded(Prompt prompt) {
        return new ToolCallLimitExceededException(null, budget.maxRounds(),
                DefaultToolExecutionResult.builder()
                        .conversationHistory(new ArrayList<>(prompt.getInstructions()))
                        .build());
    }

    /** 模型这一轮想调哪个工具（"最后一轮还在调工具"的提示里要说得出工具名）。 */
    private static String toolNameOf(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "?";
        }
        var calls = response.getResult().getOutput().getToolCalls();
        return calls == null || calls.isEmpty() ? "?" : calls.get(0).name();
    }

    /** 模型这一轮说了什么（调工具前的那段文字；原生协议没有 thought 字段，只能用这个接近它）。 */
    private static String assistantText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    /** 停在哪一维 + 额度 + 实际用量 —— 报告里必须说得出具体维度。 */
    private String describeStop() {
        String dimension = budget.stopReason().map(BudgetGuard.Dimension::label).orElse("未知维度");
        return "预算耗尽（" + dimension + "）：" + budget.describeLimits()
                + "；实际用了 轮次 " + rounds + " · token " + budget.totalTokens();
    }

    public boolean stopped() {
        return stopDetail != null;
    }

    /** 停的原因是不是"最后一轮还在调工具"（引擎据此把它归到轮次预算）。 */
    public boolean lastRoundStillCallingTools() {
        return lastRoundStillCallingTools;
    }

    public String stopDetail() {
        return stopDetail;
    }

    public int rounds() {
        return rounds;
    }
}
