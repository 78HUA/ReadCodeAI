package com.readcodeai.agent;

import com.readcodeai.agent.model.AgentAnswer;
import com.readcodeai.agent.model.AgentMode;
import com.readcodeai.agent.model.AgentStep;
import com.readcodeai.agent.model.AnsweredBy;
import com.readcodeai.agent.model.AskEvidence;
import com.readcodeai.agent.model.StopReason;
import com.readcodeai.agent.model.SupportCheck;
import com.readcodeai.agent.model.VerificationSummary;
import com.readcodeai.agent.tools.ToolContext;
import com.readcodeai.agent.tools.ToolResult;
import com.readcodeai.config.BudgetGuard;
import com.readcodeai.config.LlmClient;
import com.readcodeai.evidence.EvidenceVerifier;
import com.readcodeai.evidence.SupportChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 手写的多跳 tool-calling 循环 —— **这一步才让项目变成 Agent**。
 *
 * <p>一次问答变成一串自主决定：模型看到问题 → 决定查什么（调一个工具）→ 看到结果 →
 * 再决定下一步查什么 → 觉得够了就给结论。**跳几跳、往哪跳、什么时候停，是模型决定的**，
 * 我们只负责两件事：给它能查的工具，和把它框住。
 *
 * <h3>四条约束（缺一条这个循环就会退化成烧钱的死循环）</h3>
 * <ol>
 *   <li>{@link VisitedEdgeSet 环检测}：同一条边不重复走。调用图里本来就有环（互相调用、递归），
 *       而模型没有"我来过这里"的记忆 —— 不挡住它就会在两个方法之间来回跳</li>
 *   <li>{@link BudgetGuard 四维预算}：轮次 / 时长 / token / 成本，任一超限即停</li>
 *   <li><b>每一跳都带证据</b>：证据是工具查出来的（索引 + 磁盘），不是模型复述的</li>
 *   <li><b>结论必须带证据并通过磁盘核验</b>：对不上就退回让它重发一次，再对不上就拒答</li>
 * </ol>
 *
 * <h3>刻意不做的事</h3>
 * <p>不引任何 AI 框架的 function-calling 协议 —— 协议是我们自己在提示词里约定的 JSON。
 * 代价是模型偶尔不守格式（有重发与降级兜着），换来的是**换模型换供应商都不改代码**，
 * 而且整个循环是我们可以读、可以测、可以在面试里讲清楚的一段普通 Java 代码。
 */
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    /** 单条观察写进提示词前的截断长度：不截断的话，一次 readSymbol 就能把上下文预算吃光。 */
    private static final int MAX_OBSERVATION_CHARS = 4000;

    /** 格式错误重发的次数上限（**有限次**：模型要是学不会，继续问也只是烧钱）。 */
    private static final int MAX_FORMAT_RETRIES = 1;

    /** 证据核验失败后退回重发的次数上限。 */
    private static final int MAX_VERIFICATION_CORRECTIONS = 1;

    /** 连续重复同一次查询多少次就判定为绕圈。给到 3 是因为模型偶尔要挨两次提醒才肯改道。 */
    private static final int MAX_CONSECUTIVE_REPEATS = 3;

    /**
     * 收尾提示里附带的证据写法样例。
     *
     * <p>为什么要给样例：实测小模型会**卡在输出格式上** —— 结论那支要求嵌套的 {@code final}
     * 与 evidence 数组，比工具调用那支复杂，于是它宁可一直调工具也不收尾。
     * 把"可以不带 snippet"这件事用一个具体例子摆出来，比在系统提示里写一条规则管用。
     */
    private static final String CONCLUSION_EXAMPLE = """

            给结论时 evidence 这样写（snippet 可以留空，留空只核验文件与行号）：
            "final":{"answer":"结论","evidence":[{"file":"上面某条记录里的文件","startLine":12,"endLine":12,"snippet":"","why":"说明"}],"refused":false,"refusalReason":""}""";

    private final ToolRegistry toolRegistry;
    private final EvidenceVerifier evidenceVerifier;
    private final SupportChecker supportChecker;
    private final LlmClient llmClient;

    /** 提示词里保留多少跳的**原始输出**；更早的压成一行事实（0 = 不压缩，旧行为）。 */
    private final int keepFullObservations;

    /** 压缩后的"一行事实"里最多列几个查到的符号/证据位置（测试会引用它们来断言"该留的都留了"）。 */
    static final int COMPACT_KEEP_SUBJECTS = 12;
    static final int COMPACT_KEEP_LOCATIONS = 5;

    public AgentLoop(ToolRegistry toolRegistry, EvidenceVerifier evidenceVerifier,
                     SupportChecker supportChecker, LlmClient llmClient, int keepFullObservations) {
        this.toolRegistry = toolRegistry;
        this.evidenceVerifier = evidenceVerifier;
        this.supportChecker = supportChecker;
        this.llmClient = llmClient;
        this.keepFullObservations = Math.max(0, keepFullObservations);
    }

    /**
     * 提示词里的一行：**真跳**（带结构化 step，可按需压缩）或系统说明（格式问题、绕圈提醒等，永远保留全文）。
     *
     * <p>为什么要把这两类分开：压缩只该针对"代码文本"，而系统说明本身就是一行字，压不出东西来。
     * 早先它们混在一个 {@code List<String>} 里，想压缩就只能靠字符串前缀猜，那是碰运气。
     */
    private record PromptLine(String text, AgentStep step) {

        static PromptLine note(String text) {
            return new PromptLine(text, null);
        }

        static PromptLine hop(AgentStep step, String text) {
            return new PromptLine(text, step);
        }
    }

    /**
     * @param seeds 确定性路由给出的已知线索（第 0 跳）。它让模型不必再花一轮去"猜问题里的符号是哪个"，
     *              也顺带展示了确定性层与模型层的分工：**能算准的先算，模型从算准的地方起步**
     */
    public AgentAnswer run(long repoId, Path repoRoot, String question, Seeds seeds,
                           BudgetGuard budget) {
        long startNanos = System.nanoTime();
        ToolContext context = new ToolContext(repoId, repoRoot);
        List<AgentStep> steps = new ArrayList<>();
        List<PromptLine> transcript = new ArrayList<>();
        seeds.lines().forEach(line -> transcript.add(PromptLine.note(line)));
        VisitedEdgeSet visited = new VisitedEdgeSet();
        // 轨迹状态：看到过的符号 vs 已经查过的符号。两者之差就是「还没走的路」——
        // 模型绕圈时把它算出来甩给模型，比只说一句"你重复了"有用得多（实测模型真的需要这一步）
        Set<String> discovered = new LinkedHashSet<>();
        Set<String> queried = new LinkedHashSet<>();
        String systemPrompt = systemPrompt();

        int hop = 0;
        int formatRetries = 0;
        int corrections = 0;
        int consecutiveRepeats = 0;
        StopReason stop = null;
        String stopDetail = null;

        while (stop == null) {
            if (!budget.canContinue()) {
                stop = budgetStopReason(budget);
                stopDetail = "预算耗尽（" + budget.stopReason().map(BudgetGuard.Dimension::label).orElse("?")
                        + "）：" + budget.describeLimits();
                break;
            }

            LlmClient.Completion completion;
            try {
                completion = llmClient.complete(systemPrompt,
                        userPrompt(question, transcript, visited, budget));
            } catch (RuntimeException e) {
                // 调用故障与"仓库里没有答案"必须分开报 —— 不然使用者会把网络问题当成分析结论
                log.warn("多跳第 {} 轮模型调用失败：{}", budget.usage().rounds() + 1, e.toString());
                stop = StopReason.LLM_CALL_FAILED;
                stopDetail = "模型调用失败（" + e.getClass().getSimpleName() + "：" + e.getMessage()
                        + "）。这是调用故障，不是「仓库里没有答案」。";
                break;
            }
            budget.recordLlmCall(completion.promptTokens(), completion.completionTokens());

            ModelJson.Turn turn;
            try {
                turn = ModelJson.parse(completion.content(), ModelJson.Turn.class);
            } catch (ModelOutputFormatException e) {
                if (formatRetries < MAX_FORMAT_RETRIES && budget.canContinue()) {
                    formatRetries++;
                    log.warn("模型输出不是合法 JSON，重发一次提示：{}", e.getMessage());
                    transcript.add(PromptLine.note("[格式问题] 你上一条输出不是合法 JSON（" + e.getMessage()
                            + "）。请**只**输出一个 JSON 对象，不要代码块、不要解释文字。"));
                    continue;
                }
                stop = StopReason.FORMAT_ERROR;
                stopDetail = "模型输出不是合法 JSON，多跳中止：" + e.getMessage();
                break;
            }

            if (!turn.hasAction() && !turn.hasFinal()) {
                if (formatRetries < MAX_FORMAT_RETRIES && budget.canContinue()) {
                    formatRetries++;
                    transcript.add(PromptLine.note("[格式问题] 你的输出里既没有 tool 也没有 final。"
                            + "请输出 {\"thought\":\"...\",\"tool\":\"findCallers\",\"args\":{\"symbol\":\"...\"}}"
                            + " 或 {\"thought\":\"...\",\"final\":{\"answer\":\"...\",\"evidence\":[...]}}"));
                    continue;
                }
                stop = StopReason.FORMAT_ERROR;
                stopDetail = "模型既没有调用工具也没有给出结论，多跳中止";
                break;
            }

            // ---- 工具调用分支 ----
            if (turn.hasAction()) {
                hop++;
                String toolName = turn.toolName();
                String argsKey = turn.argsKey();

                // 最后一轮不再执行工具：这一轮是留给**结论**的。
                // 实测（真实模型）会出现"一直查到被闸门掐断、一条结论都没给出"，
                // 那样多跳就只剩"查了一半"，还不如让它带着不完整的材料给出结论 —— 至少能说清查到了什么。
                if (budget.remainingRounds() <= 1) {
                    stop = StopReason.BUDGET_ROUNDS;
                    stopDetail = "最后一轮模型仍然在调用工具（" + toolName + "），没有给出结论，"
                            + "已查到的轨迹一并交出";
                    break;
                }

                boolean repeated = !visited.firstVisit(toolName, argsKey);
                budget.recordToolCall(repeated);

                if (repeated) {
                    consecutiveRepeats++;
                    String note = "这个查询已经做过了（" + VisitedEdgeSet.key(toolName, argsKey)
                            + "），结果就在上面的记录里，重复调用不会得到新信息。" + nextMoves(discovered, queried);
                    transcript.add(PromptLine.note(note));
                    steps.add(new AgentStep(hop, turn.thought(), toolName, argsKey, note,
                            List.of(), List.of(), true, 0));
                    log.info("多跳第 {} 跳：重复调用被环检测拦下 —— {}", hop, argsKey);
                    if (consecutiveRepeats >= MAX_CONSECUTIVE_REPEATS) {
                        stop = StopReason.NO_PROGRESS;
                        stopDetail = "模型连续 " + consecutiveRepeats + " 次重复同一次查询，判定为绕圈并主动终止"
                                + "（环检测的意义就在这：不终止的话它会一直烧预算）";
                    }
                    continue;
                }
                consecutiveRepeats = 0;
                queried.add(VisitedEdgeSet.normalize(argsKey));

                long toolStart = System.nanoTime();
                ToolResult result = toolRegistry.execute(context, toolName, turn.toolArgs());
                long toolMillis = (System.nanoTime() - toolStart) / 1_000_000;
                String observation = clip(result.observation());
                AgentStep step = new AgentStep(hop, turn.thought(), toolName, argsKey, observation,
                        result.evidence(), result.subjects(), false, toolMillis);
                transcript.add(PromptLine.hop(step,
                        "[第 " + hop + " 跳] " + toolName + "(" + argsKey + ") →\n" + observation));
                result.subjects().forEach(subject -> discovered.add(VisitedEdgeSet.normalize(subject)));
                steps.add(step);
                log.info("多跳第 {} 跳：{}({}) → {} 个结果（{} ms）",
                        hop, toolName, argsKey, result.subjects().size(), toolMillis);
                continue;
            }

            // ---- 结论分支 ----
            ModelJson.FinalAnswer finalAnswer = turn.effectiveFinal();
            if (Boolean.TRUE.equals(finalAnswer.refused())) {
                stop = StopReason.REFUSED_BY_MODEL;
                stopDetail = finalAnswer.refusalReason() == null || finalAnswer.refusalReason().isBlank()
                        ? "模型判断现有材料不足以回答" : finalAnswer.refusalReason();
                break;
            }

            List<AskEvidence> cited = ModelJson.validEvidence(finalAnswer.evidence());
            if (cited.isEmpty()) {
                stop = StopReason.NO_EVIDENCE;
                stopDetail = "模型给出了结论但没有提供可核验的证据（file + 行号）";
                break;
            }

            // ---- 分水岭：真读磁盘核验（多跳也不例外）----
            EvidenceVerifier.Report report = evidenceVerifier.verify(repoRoot, cited);
            List<AskEvidence> accepted = report.evidence().stream()
                    .filter(EvidenceVerifier.VerifiedEvidence::passed)
                    .map(EvidenceVerifier.VerifiedEvidence::evidence)
                    .collect(Collectors.toCollection(ArrayList::new));

            // 第二道：**引用必须落在本次轨迹给过的位置里**。
            // ①②层只能证明"这几行真的存在"，证明不了"模型是从给它的材料里引的" ——
            // 实测撞到过：模型把结论挂在一个它从没查过的位置上（例如整个类的声明行），
            // 文件行号都有效，所以前两层一路放行，但那条证据其实没有任何依据。
            List<AskEvidence> grounded = new ArrayList<>();
            List<String> ungrounded = new ArrayList<>();
            for (AskEvidence evidence : accepted) {
                if (coveredByLocation(seeds.evidence(), evidence) || coveredByTrail(steps, evidence)) {
                    grounded.add(evidence);
                } else {
                    ungrounded.add(evidence.location());
                }
            }
            if (!ungrounded.isEmpty()) {
                log.warn("结论里有 {} 条证据不在本次轨迹给过的范围内：{}", ungrounded.size(), ungrounded);
            }
            accepted = grounded;

            if (accepted.isEmpty()) {
                if (corrections < MAX_VERIFICATION_CORRECTIONS && budget.canContinue()) {
                    corrections++;
                    transcript.add(PromptLine.note("[核验失败] 你引用的证据没有通过磁盘核对：\n"
                            + describeFailures(report)
                            + "\n请用 readSymbol 把相关代码**重新读一遍**，再照抄原文与行号重发结论。"));
                    log.warn("多跳：模型证据全部未通过核验，退回重发一次");
                    continue;
                }
                stop = StopReason.EVIDENCE_REJECTED;
                stopDetail = "结论的全部证据都未通过磁盘核验（文件、行号或片段对不上）";
                break;
            }

            log.info("多跳完成：{} 轮 · {} 跳（重复 {} 次）· 证据 {}/{} 条通过核验",
                    budget.usage().rounds(), budget.usage().toolCalls(), budget.usage().repeatedCalls(),
                    accepted.size(), cited.size());

            // ---- ③ 层：这段代码**支持**这条结论吗 ----
            // 前两层证明的是"这几行真实存在、模型也是从给它的材料里引的"，
            // 证明不了"这几行说的就是结论说的那件事" —— 实测撞到过最贵的一类错误，
            // 只有这一层能发现（见 SupportCheck 的注释）。
            SupportCheck support = runSupportCheck(question, finalAnswer.answer(), accepted, repoRoot);
            if (support.flagged() && supportChecker.rejectOnUnsupported()) {
                log.warn("③ 层判定为不支持，按拒答处理（readcodeai.verify.support-check=reject）：{}",
                        support.reason());
                // 拒答但**把被引的证据一并交出**：使用者要能自己看一眼，判官凭什么说它不支持
                return new AgentAnswer(null, accepted, true,
                        "证据通过了磁盘核验，但 ③ 层判定它不支持这条结论：" + support.reason(),
                        AnsweredBy.LLM, AgentMode.MULTI_HOP, steps, budget.usage().rounds(),
                        budget.usage().toolCalls(), budget.usage().repeatedCalls(),
                        StopReason.SUPPORT_REJECTED,
                        (int) budget.usage().promptTokens(), (int) budget.usage().completionTokens(),
                        budget.usage().estimatedCost(), elapsedMillis(startNanos),
                        new VerificationSummary(accepted.size(), report.failed(), List.of(), support),
                        false, null);
            }

            return new AgentAnswer(finalAnswer.answer(), accepted, false, null, AnsweredBy.LLM,
                    AgentMode.MULTI_HOP, steps, budget.usage().rounds(), budget.usage().toolCalls(),
                    budget.usage().repeatedCalls(), StopReason.FINAL,
                    (int) budget.usage().promptTokens(), (int) budget.usage().completionTokens(),
                    budget.usage().estimatedCost(), elapsedMillis(startNanos),
                    new VerificationSummary(accepted.size(), report.failed(),
                            corrections > 0 ? List.of("按核验失败提示重发过一次结论") : List.of(),
                            support),
                    false, null);
        }

        // ---- 停机：**不假装答出来了**，但轨迹里查到的东西一并交出来（有材料 ≠ 有结论）----
        List<AskEvidence> trail = trailEvidence(steps);
        String reason = stopDetail + (trail.isEmpty()
                ? "（轨迹里也没有查到相关符号）"
                : "（轨迹里已查到 " + trail.size() + " 条证据，但没有形成带证据的结论，故不给出结论）");
        log.info("多跳终止：stop={} · 轮次 {} · 跳数 {} · 原因：{}", stop, budget.usage().rounds(),
                budget.usage().toolCalls(), stopDetail);
        return new AgentAnswer(null, trail, true, reason, AnsweredBy.LLM, AgentMode.MULTI_HOP, steps,
                budget.usage().rounds(), budget.usage().toolCalls(), budget.usage().repeatedCalls(),
                stop, (int) budget.usage().promptTokens(), (int) budget.usage().completionTokens(),
                budget.usage().estimatedCost(), elapsedMillis(startNanos), VerificationSummary.none(),
                false, null);
    }

    /**
     * ③ 层判定的统一入口（与单跳路径同一套兜底）：**核验器自己崩了不能让这次问答失败**。
     *
     * <p>崩了记成"这次没核验成"，而前两层已经证明了证据真实存在 ——
     * 那个结论不该被一次判定故障抹掉。
     */
    private SupportCheck runSupportCheck(String question, String answer, List<AskEvidence> evidence,
                                         Path repoRoot) {
        try {
            return supportChecker.check(question, answer, evidence, repoRoot);
        } catch (RuntimeException e) {
            log.warn("③ 层核验器异常（按「未完成」记录，不影响本次回答）：{}", e.toString());
            return SupportCheck.unavailable("核验器异常（" + e.getClass().getSimpleName() + "）");
        }
    }

    private static StopReason budgetStopReason(BudgetGuard budget) {
        return switch (budget.stopReason().orElse(BudgetGuard.Dimension.ROUNDS)) {
            case ROUNDS -> StopReason.BUDGET_ROUNDS;
            case DURATION -> StopReason.BUDGET_DURATION;
            case TOKENS -> StopReason.BUDGET_TOKENS;
            case COST -> StopReason.BUDGET_COST;
        };
    }

    /**
     * 第 0 跳的线索：给模型看的文字，**加上它因此看到的位置**。
     *
     * <p>后者是用来判"引用有没有依据"的：种子把目标符号的定义行交到模型手上，
     * 那它引用这个定义就是有据可依 —— 不把这部分算进去，会把合法引用误杀。
     */
    public record Seeds(List<String> lines, List<AskEvidence> evidence) {

        public static Seeds none() {
            return new Seeds(List.of(), List.of());
        }

        public static Seeds of(List<String> lines, List<AskEvidence> evidence) {
            return new Seeds(lines, evidence);
        }
    }

    private static List<AskEvidence> trailEvidence(List<AgentStep> steps) {
        List<AskEvidence> all = new ArrayList<>();
        for (AgentStep step : steps) {
            all.addAll(step.evidence());
        }
        return all;
    }

    private String systemPrompt() {
        return """
                你是代码库理解 Agent。你的任务**不是回忆代码**，而是用工具把事实查出来，再依据查到的东西回答。

                每一步只能输出**一个** JSON 对象，两种形态之一：

                1) 继续查（调用一个工具）：
                {"thought":"为什么查这个","tool":"findCallers","args":{"symbol":"类名.方法名"}}

                2) 给出结论：
                {"thought":"...","final":{"answer":"结论","evidence":[{"file":"文件路径","startLine":1,"endLine":2,"snippet":"逐字照抄的原文","why":"这段说明了什么"}],"refused":false,"refusalReason":""}}

                """ + toolRegistry.describeForPrompt() + """

                硬性规则：
                1. 只输出 JSON，不要 Markdown 代码块，不要 JSON 以外的任何文字。
                2. 每轮只调用一个工具；**同一个调用不要重复**（重复会被拒绝，白白消耗预算）。
                3. 结论里的每条证据必须给出 file + startLine + endLine。
                   snippet 是**可选**的：从工具结果里逐字照抄那几行（对不上就作废），
                   不想给就留空字符串 —— 留空只核验文件与行号，不算错。
                4. 需要沿调用链向上追的问题（"这个值/这个参数从哪来"），就用 findCallers 一跳一跳往上，
                   直到够用为止 —— 追几跳由你判断。
                5. 材料够回答就**停下给结论**，不要为了多查而多查。
                   轮次是有限的（每轮提示里会告诉你还剩几轮），剩最后一轮时必须给结论。
                6. 材料不足以回答时，输出 refused=true，并在 refusalReason 里说明**缺什么**。不要猜、不要编。
                """;
    }

    private String userPrompt(String question, List<PromptLine> transcript, VisitedEdgeSet visited,
                              BudgetGuard budget) {
        StringBuilder prompt = new StringBuilder("问题：").append(question).append("\n");
        if (!transcript.isEmpty()) {
            prompt.append("\n[已经查到的事实]（都是工具查出来的，可信）\n");
            Set<Integer> compacted = hopsToCompact(transcript);
            for (PromptLine line : transcript) {
                if (line.step() != null && compacted.contains(line.step().hop())) {
                    prompt.append(compact(line.step())).append("\n");
                } else {
                    prompt.append(line.text()).append("\n");
                }
            }
        }
        if (visited.size() > 0) {
            prompt.append("\n[已做过的查询，不要重复] ").append(String.join("、", visited.labels(12))).append("\n");
        }
        // 把剩余轮次明说：实测模型不知道上限时会一路查下去，最后被闸门掐断、一条结论都没给出
        prompt.append("\n还能查 ").append(budget.remainingRounds()).append(" 轮（每轮一次工具调用或一次结论）。");
        if (budget.remainingRounds() <= 1) {
            prompt.append("**这就是最后一轮：必须给结论（final），不要再用工具。**")
                    .append(CONCLUSION_EXAMPLE);
        } else if (budget.remainingRounds() <= 2) {
            prompt.append("快到头了：够用就给结论。");
        }
        prompt.append("\n下一步：只输出一个 JSON 对象"
                + "（调工具：{\"thought\":...,\"tool\":...,\"args\":{...}}；"
                + "给结论：{\"thought\":...,\"final\":{...}}）。");
        return prompt.toString();
    }

    /**
     * 哪些轮次该压缩：**只保留最近 {@code keepFullObservations} 跳的原始输出**。
     *
     * <p>为什么值得压：每轮都要把整份记录重发一遍，而记录里绝大部分是旧轮次的代码文本 ——
     * 实测单题 6k–15k token 就是这么来的，token 随轮数近似**平方**增长。
     * 而模型拿旧轮次做什么用？知道"查到了哪些名字、下一步往哪走"。**名字与位置留下、代码文本丢掉**，
     * 该有的信息一条不少（证据本身仍完整地留在轨迹里，grounding 与磁盘核验用的都是轨迹，不是提示词）。
     *
     * @return 该压缩的跳号；{@code keepFullObservations <= 0} 时返回空集 = 全部保留（旧行为）
     */
    private Set<Integer> hopsToCompact(List<PromptLine> transcript) {
        if (keepFullObservations <= 0) {
            return Set.of();
        }
        List<AgentStep> hopSteps = transcript.stream()
                .map(PromptLine::step)
                .filter(java.util.Objects::nonNull)
                .toList();
        Set<Integer> compacted = new LinkedHashSet<>();
        for (int i = 0; i < hopSteps.size(); i++) {
            if (hopSteps.size() - 1 - i >= keepFullObservations) {
                compacted.add(hopSteps.get(i).hop());
            }
        }
        return compacted;
    }

    /**
     * 把一轮的原始输出压成**一行事实**：跳数、工具、参数、查到的名字、证据位置。
     *
     * <p>丢弃的是代码文本（它最长、也最容易让上下文爆掉），保留的是**可继续推理的骨架**。
     * 末尾那句提示是必要的：模型会以为"上面没给的就是没有"，明说"要不要我重查"比让它猜强。
     */
    private static String compact(AgentStep step) {
        StringBuilder line = new StringBuilder("[第 ").append(step.hop()).append(" 跳] ")
                .append(step.tool()).append('(').append(step.args()).append(") → ");
        if (step.subjects().isEmpty()) {
            line.append("没有查到新的符号");
        } else {
            line.append(step.subjects().size()).append(" 个结果：")
                    .append(step.subjects().stream().limit(COMPACT_KEEP_SUBJECTS)
                            .collect(Collectors.joining("、")));
            if (step.subjects().size() > COMPACT_KEEP_SUBJECTS) {
                line.append(" 等");
            }
        }
        if (!step.evidence().isEmpty()) {
            line.append(" · 证据位置：")
                    .append(step.evidence().stream().limit(COMPACT_KEEP_LOCATIONS)
                            .map(AskEvidence::location).collect(Collectors.joining("、")));
            if (step.evidence().size() > COMPACT_KEEP_LOCATIONS) {
                line.append(" 等");
            }
        }
        line.append("（这一跳的原始输出已略去以省上下文；需要细节可用同样的参数重新查一次）");
        return line.toString();
    }

    /**
     * 把「还没查过的路」算给模型看；一条不剩就明确让它收尾。
     *
     * <p>这里**不再重复附结论格式样例**：它在提示词的收尾段已经出现过，每轮再追加一遍纯属浪费
     * （实测每轮多几百字符，而模型第一次就照抄下来了）。
     */
    private static String nextMoves(Set<String> discovered, Set<String> queried) {
        List<String> options = discovered.stream()
                .filter(symbol -> !queried.contains(symbol))
                .limit(5)
                .toList();
        if (options.isEmpty()) {
            return "已经查到的分支都走过了 —— 现在直接给结论（final），不要再调用工具。";
        }
        return "还没查过的上游有：" + String.join("、", options)
                + " —— 对其中一个继续 findCallers，或者直接给结论。";
    }

    /**
     * 这条引用有没有落在本次轨迹给过的位置里（被某个工具返回的区间**包含**）。
     *
     * <p>这就是单跳路径里那条"模型只能引用给它的片段"的规矩，在多跳里同样是防幻觉的第一道闸门 ——
     * 而且它比磁盘核验更早生效：磁盘核验管"这一行存不存在"，它管"这句话是不是有据可依"。
     */
    private static boolean coveredByTrail(List<AgentStep> steps, AskEvidence evidence) {
        for (AgentStep step : steps) {
            if (coveredByLocation(step.evidence(), evidence)) {
                return true;
            }
        }
        return false;
    }

    private static boolean coveredByLocation(List<AskEvidence> given, AskEvidence evidence) {
        for (AskEvidence item : given) {
            if (item.file().equals(evidence.file())
                    && item.startLine() <= evidence.startLine()
                    && item.endLine() >= evidence.endLine()) {
                return true;
            }
        }
        return false;
    }

    /** 把核验失败的原因说清楚 —— 模型据此**定向修正**（哪一行对不上），而不是盲目重试一遍。 */
    private static String describeFailures(EvidenceVerifier.Report report) {
        return report.evidence().stream()
                .filter(verified -> !verified.passed())
                .map(verified -> "- " + verified.evidence().location() + " ：" + verified.failureKind()
                        + (verified.detail() == null ? "" : "（" + verified.detail() + "）"))
                .collect(Collectors.joining("\n"));
    }

    private static String clip(String observation) {
        if (observation == null) {
            return "";
        }
        return observation.length() <= MAX_OBSERVATION_CHARS
                ? observation : observation.substring(0, MAX_OBSERVATION_CHARS) + "\n…（观察过长，已截断）";
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
