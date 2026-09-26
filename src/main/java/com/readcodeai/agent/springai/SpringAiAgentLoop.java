package com.readcodeai.agent.springai;

import com.readcodeai.agent.AgentEngine;
import com.readcodeai.agent.AgentLoop;
import com.readcodeai.agent.LlmUnavailableException;
import com.readcodeai.agent.ModelJson;
import com.readcodeai.agent.ModelOutputFormatException;
import com.readcodeai.agent.ToolRegistry;
import com.readcodeai.agent.model.AgentAnswer;
import com.readcodeai.agent.model.AgentMode;
import com.readcodeai.agent.model.AnsweredBy;
import com.readcodeai.agent.model.AskEvidence;
import com.readcodeai.agent.model.StopReason;
import com.readcodeai.agent.model.SupportCheck;
import com.readcodeai.agent.model.VerificationSummary;
import com.readcodeai.agent.tools.ToolContext;
import com.readcodeai.config.BudgetGuard;
import com.readcodeai.evidence.EvidenceVerifier;
import com.readcodeai.evidence.SupportChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI 版的多跳引擎：**框架跑工具循环**，四类约束用它的扩展点实现。
 *
 * <h3>与手写版（{@link AgentLoop}）的对应关系</h3>
 * <ul>
 *   <li>循环本体 + 工具协议 → 框架的 ToolCallingAdvisor（**这 800 多行不写了**）</li>
 *   <li>四维预算 → {@link BudgetToolCallingManager}（注册 Bean 覆盖默认执行器）</li>
 *   <li>环检测 + 每跳带证据 → {@link SpringAiLoopState}（与手写版同一个 {@code VisitedEdgeSet}）</li>
 *   <li>结论证据核验 +（不过就）退回重发 → 本类循环外的两轮调用（框架一次调用出一次结论，
 *       重发只能在它外面再包一层 —— 这与手写版里 continue 一次的语义等价）</li>
 *   <li>确定性路由的种子、"引用必须有据可依"、③ 层核验 → 全部原样复用（框架碰不到它们）</li>
 * </ul>
 *
 * <p><b>降级纪律不变</b>：没配 Key 时 Spring AI 不会创建模型 Bean，本类构造不出来时
 * {@link AgentService} 也不会走到它；真走到这里则抛 {@link LlmUnavailableException}，与手写版一致。
 */
@Component
@ConditionalOnProperty(name = "readcodeai.agent.engine", havingValue = "spring-ai")
public class SpringAiAgentLoop implements AgentEngine {

    private static final Logger log = LoggerFactory.getLogger(SpringAiAgentLoop.class);

    /** 核验失败/格式错误各只给一次改正机会（与手写版同样的"有限次"）。 */
    private static final int MAX_CORRECTIONS = 1;

    /**
     * 系统提示词：**只有"怎么收尾"和硬性规则**，工具清单改由原生协议提供
     * （手写版要在这里手写工具清单和 {"tool":...} 协议，这是最直接的差别）。
     */
    private static final String SYSTEM_PROMPT = """
            你是代码库理解 Agent。你的任务**不是回忆代码**，而是用工具把事实查出来，再依据查到的东西回答。

            每一轮你可以：调用一个工具继续查；或者**只输出一个 JSON** 给出结论：
            {"final":{"answer":"结论","evidence":[{"file":"文件路径","startLine":1,"endLine":2,"snippet":"逐字照抄的原文","why":"这段说明了什么"}],"refused":false,"refusalReason":""}}

            硬性规则：
            1. 给结论时**只输出 JSON**，不要 Markdown 代码块，不要 JSON 以外的任何文字；
            2. 每条证据必须给 file + startLine + endLine。snippet 是**可选**的：想给就从工具结果里
               **逐字照抄**那几行（对不上就作废），不想给就留空字符串 —— 留空只核验文件与行号，不算错；
            3. **需要沿调用链向上追的问题（"这个值/这个参数从哪来"），就用 findCallers 一跳一跳往上查**，
               直到够用为止 —— 追几跳由你判断；往下追影响面用 findCallees；
            4. 同一个调用不要重复（重复会被拒绝，白白消耗预算）；材料够回答就**停下给结论**，不要为了多查而多查；
            5. 材料足以回答时**必须给出结论**（"无法确定"不是结论：把查到的事实讲清楚，并给出它们的位置）；
               确实材料不足才输出 refused=true，并在 refusalReason 里说明**缺什么**。不要猜、不要编。
            """;

    private final ObjectProvider<ChatClient.Builder> chatClientBuilder;
    private final ToolRegistry toolRegistry;
    private final EvidenceVerifier evidenceVerifier;
    private final SupportChecker supportChecker;

    public SpringAiAgentLoop(ObjectProvider<ChatClient.Builder> chatClientBuilder,
                             ToolRegistry toolRegistry,
                             EvidenceVerifier evidenceVerifier,
                             SupportChecker supportChecker) {
        this.chatClientBuilder = chatClientBuilder;
        this.toolRegistry = toolRegistry;
        this.evidenceVerifier = evidenceVerifier;
        this.supportChecker = supportChecker;
    }

    @Override
    public AgentAnswer run(long repoId, Path repoRoot, String question, AgentLoop.Seeds seeds,
                           BudgetGuard budget) {
        long startNanos = System.nanoTime();
        ChatClient.Builder builder = chatClientBuilder.getIfAvailable();
        if (builder == null) {
            throw new LlmUnavailableException("Spring AI 引擎需要 spring.ai.openai.*（当前未配置模型）");
        }

        SpringAiLoopState state = new SpringAiLoopState(toolRegistry, new ToolContext(repoId, repoRoot), budget);
        SpringAiToolAdapter tools = new SpringAiToolAdapter(state);
        BudgetToolCallingManager manager = new BudgetToolCallingManager(budget, state);
        ChatClient client = builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(ToolCallingAdvisor.builder().toolCallingManager(manager).build())
                .build();

        List<Message> history = new ArrayList<>();
        history.add(new UserMessage(userText(question, seeds)));

        for (int attempt = 0; attempt <= MAX_CORRECTIONS; attempt++) {
            ChatResponse response;
            try {
                response = client.prompt(new Prompt(history)).tools(tools).call().chatResponse();
            } catch (RuntimeException e) {
                log.warn("[SpringAI] 模型调用失败：{}", e.toString());
                return refused(state, StopReason.LLM_CALL_FAILED,
                        "模型调用失败（" + e.getClass().getSimpleName() + "：" + e.getMessage()
                                + "）。这是调用故障，不是「仓库里没有答案」。", budget, startNanos, null);
            }

            if (manager.stopped()) {
                // 预算闸门中断：**不假装答出来了**，但轨迹里查到的证据一并交出
                StopReason reason = budgetStopReason(budget, manager);
                return refused(state, reason, manager.stopDetail(), budget, startNanos, null);
            }

            String content = text(response);
            log.info("[SpringAI] 模型输出（{} 字）：{}", content == null ? 0 : content.length(), clip(content, 600));

            // ---- 解析结论 ----
            ModelJson.Turn turn;
            try {
                turn = ModelJson.parse(content, ModelJson.Turn.class);
            } catch (ModelOutputFormatException e) {
                String note = "[格式问题] 你上一条输出不是合法 JSON（" + e.getMessage()
                        + "）。请**只**输出 {\"final\":{...}} 这一个 JSON 对象，不要代码块、不要解释文字。";
                if (attempt < MAX_CORRECTIONS) {
                    log.warn("[SpringAI] 输出不是合法 JSON，退回重发一次");
                    history.add(new AssistantMessage(content == null ? "" : content));
                    history.add(new UserMessage(note));
                    continue;
                }
                return refused(state, StopReason.FORMAT_ERROR, "模型输出不是合法 JSON（提示后仍然如此）",
                        budget, startNanos, null);
            }

            ModelJson.FinalAnswer finalAnswer = turn.effectiveFinal();
            if (finalAnswer == null) {
                String note = "[格式问题] 你没有给结论。要收尾就只输出 {\"final\":{...}} 这一个 JSON；要继续查就调工具。";
                if (attempt < MAX_CORRECTIONS) {
                    history.add(new AssistantMessage(content == null ? "" : content));
                    history.add(new UserMessage(note));
                    continue;
                }
                return refused(state, StopReason.FORMAT_ERROR, "模型既没给结论也没调工具（输出无法解释）",
                        budget, startNanos, null);
            }
            if (Boolean.TRUE.equals(finalAnswer.refused())) {
                String why = finalAnswer.refusalReason() == null || finalAnswer.refusalReason().isBlank()
                        ? "模型判断现有材料不足以回答" : finalAnswer.refusalReason();
                return refused(state, StopReason.REFUSED_BY_MODEL, why, budget, startNanos, null);
            }

            List<AskEvidence> cited = ModelJson.validEvidence(finalAnswer.evidence());
            if (cited.isEmpty()) {
                return refused(state, StopReason.NO_EVIDENCE,
                        "模型给出了结论但没有提供可核验的证据（file + 行号）", budget, startNanos, null);
            }

            // ---- 分水岭：真读磁盘核验（与手写版同一个核验器）----
            EvidenceVerifier.Report report = evidenceVerifier.verify(repoRoot, cited);
            List<AskEvidence> accepted = report.evidence().stream()
                    .filter(EvidenceVerifier.VerifiedEvidence::passed)
                    .map(EvidenceVerifier.VerifiedEvidence::evidence)
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            // 第二道：引用必须落在本次给过的位置里（种子 + 轨迹）
            accepted = accepted.stream().filter(e -> covered(seeds, state, e)).toList();

            if (accepted.isEmpty()) {
                String note = "[核验失败] 你引用的证据没有通过磁盘核对（文件、行号或片段对不上）。"
                        + "请用 readSymbol 把相关代码**重新读一遍**，再照抄原文与行号重发结论。";
                if (attempt < MAX_CORRECTIONS && budget.canContinue()) {
                    log.warn("[SpringAI] 结论证据全部未通过核验，退回重发一次");
                    history.add(new AssistantMessage(content == null ? "" : content));
                    history.add(new UserMessage(note));
                    continue;
                }
                return refused(state, StopReason.EVIDENCE_REJECTED,
                        "结论的全部证据都未通过磁盘核验（文件、行号或片段对不上）；"
                                + "核验失败分布=" + report.failureCounts()
                                + "；模型引用=" + cited.stream().map(AskEvidence::location).toList(),
                        budget, startNanos, report);
            }

            // ---- ③ 层：这段代码支持这条结论吗（与手写版同一个核验器）----
            SupportCheck support = runSupportCheck(question, finalAnswer.answer(), accepted, repoRoot);
            if (support.flagged() && supportChecker.rejectOnUnsupported()) {
                return new AgentAnswer(null, accepted, true,
                        "证据通过了磁盘核验，但 ③ 层判定它不支持这条结论：" + support.reason(),
                        AnsweredBy.LLM, AgentMode.MULTI_HOP, state.steps(), budget.usage().rounds(),
                        budget.usage().toolCalls(), budget.usage().repeatedCalls(),
                        StopReason.SUPPORT_REJECTED,
                        (int) budget.usage().promptTokens(), (int) budget.usage().completionTokens(),
                        budget.usage().estimatedCost(), elapsed(startNanos),
                        new VerificationSummary(accepted.size(), report.failed(), List.of(), support),
                        false, null);
            }

            log.info("[SpringAI] 完成：{} 轮 · {} 跳 · 证据 {}/{} 条通过核验",
                    budget.usage().rounds(), budget.usage().toolCalls(), accepted.size(), cited.size());
            return new AgentAnswer(finalAnswer.answer(), accepted, false, null, AnsweredBy.LLM,
                    AgentMode.MULTI_HOP, state.steps(), budget.usage().rounds(),
                    budget.usage().toolCalls(), budget.usage().repeatedCalls(), StopReason.FINAL,
                    (int) budget.usage().promptTokens(), (int) budget.usage().completionTokens(),
                    budget.usage().estimatedCost(), elapsed(startNanos),
                    new VerificationSummary(accepted.size(), report.failed(),
                            attempt > 0 ? List.of("按核验失败提示重发过一次结论") : List.of(), support),
                    false, null);
        }
        // 理论到不了这里（循环内每个分支都 return/continue）
        return refused(state, StopReason.FORMAT_ERROR, "循环异常结束", budget, startNanos, null);
    }

    /** 停机：不假装答出来了，但轨迹里查到的东西一并交出（有材料 ≠ 有结论）。 */
    private AgentAnswer refused(SpringAiLoopState state, StopReason reason, String detail,
                                BudgetGuard budget, long startNanos, EvidenceVerifier.Report report) {
        List<AskEvidence> trail = state.trail();
        String why = (detail == null ? reason.label() : detail)
                + (trail.isEmpty() ? "（轨迹里也没有查到相关符号）"
                : "（轨迹里已查到 " + trail.size() + " 条证据，但没有形成带证据的结论，故不给出结论）");
        log.info("[SpringAI] 终止：stop={} · 轮次 {} · 跳数 {} · 原因：{}", reason,
                budget.usage().rounds(), budget.usage().toolCalls(), detail);
        return new AgentAnswer(null, trail, true, why, AnsweredBy.LLM, AgentMode.MULTI_HOP, state.steps(),
                budget.usage().rounds(), budget.usage().toolCalls(), budget.usage().repeatedCalls(),
                reason, (int) budget.usage().promptTokens(), (int) budget.usage().completionTokens(),
                budget.usage().estimatedCost(), elapsed(startNanos),
                report == null ? VerificationSummary.none()
                        : new VerificationSummary(0, report.failed(), List.of(), null),
                false, null);
    }

    private SupportCheck runSupportCheck(String question, String answer, List<AskEvidence> evidence,
                                         Path repoRoot) {
        try {
            return supportChecker.check(question, answer, evidence, repoRoot);
        } catch (RuntimeException e) {
            log.warn("③ 层核验器异常（按「未完成」记录，不影响本次回答）：{}", e.toString());
            return SupportCheck.unavailable("核验器异常（" + e.getClass().getSimpleName() + "）");
        }
    }

    /** 预算停在哪一维 → 对应的 StopReason（与手写版同一套映射）。 */
    private static StopReason budgetStopReason(BudgetGuard budget, BudgetToolCallingManager manager) {
        if (manager.lastRoundStillCallingTools()) {
            return StopReason.BUDGET_ROUNDS;
        }
        return switch (budget.stopReason().orElse(BudgetGuard.Dimension.ROUNDS)) {
            case ROUNDS -> StopReason.BUDGET_ROUNDS;
            case DURATION -> StopReason.BUDGET_DURATION;
            case TOKENS -> StopReason.BUDGET_TOKENS;
            case COST -> StopReason.BUDGET_COST;
        };
    }

    /** 引用是否有据可依：落在种子给的位置里，或落在本次轨迹查到的位置里。 */
    private static boolean covered(AgentLoop.Seeds seeds, SpringAiLoopState state, AskEvidence evidence) {
        return coveredBy(seeds.evidence(), evidence) || coveredBy(state.trail(), evidence);
    }

    private static boolean coveredBy(List<AskEvidence> given, AskEvidence evidence) {
        for (AskEvidence item : given) {
            if (item.file() != null && item.file().equals(evidence.file())
                    && item.startLine() <= evidence.startLine()
                    && item.endLine() >= evidence.endLine()) {
                return true;
            }
        }
        return false;
    }

    /** 用户提示词：问题 + 确定性路由给的种子（与手写版同一套口径）。 */
    private static String userText(String question, AgentLoop.Seeds seeds) {
        StringBuilder sb = new StringBuilder("问题：").append(question).append('\n');
        if (!seeds.lines().isEmpty()) {
            sb.append('\n');
            for (String line : seeds.lines()) {
                sb.append(line).append('\n');
            }
            sb.append("（上面这些位置就是本次已经给你的材料）\n");
        }
        return sb.toString();
    }

    /** 取模型这一轮的文本；若它同时给了工具调用，文本通常是空的。 */
    private static String text(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        String text = response.getResult().getOutput().getText();
        if (text != null && !text.isBlank()) {
            return text;
        }
        // 模型可能只给了工具调用（没有文本）：把工具调用当成"这一轮没给结论"
        return response.getResult().getOutput().hasToolCalls() ? "" : text;
    }

    private static String clip(String text, int limit) {
        if (text == null) {
            return "(空)";
        }
        String one = text.replaceAll("\\s+", " ").strip();
        return one.length() <= limit ? one : one.substring(0, limit) + "…";
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
