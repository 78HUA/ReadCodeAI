package com.readcodeai.agent.springai;

import com.readcodeai.agent.ToolRegistry;
import com.readcodeai.agent.VisitedEdgeSet;
import com.readcodeai.agent.model.AgentStep;
import com.readcodeai.agent.model.AskEvidence;
import com.readcodeai.agent.tools.ToolContext;
import com.readcodeai.agent.tools.ToolResult;
import com.readcodeai.config.BudgetGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * **一次问答的循环状态**：轨迹、证据、环检测、剩余轮次 —— 都在这里。
 *
 * <h3>为什么证据要在这里收集（这是最小迁移要验证的关键点）</h3>
 * Spring AI 只把工具的**字符串结果**回灌给模型：{@link ToolResult} 里的
 * {@code evidence}（文件 + 行号）与 {@code subjects} 不在其中。所以证据必须在
 * **工具这一侧**登记（本类的 {@link #trail}），循环结束后由引擎取走核验。
 *
 * <p>这反而比手写版更硬：**证据不经模型，就不可能被模型编造** ——
 * 模型能编的只有"结论引用了哪几条"，而那几条最终仍要过磁盘核验（①② 层）。
 *
 * <p>每问一次问答 new 一个实例：上下文（repoId/repoRoot）与收集器天然隔离，不需要 ThreadLocal。
 */
public class SpringAiLoopState {

    private static final Logger log = LoggerFactory.getLogger(SpringAiLoopState.class);

    /** 单条观察的长度上限（与手写版同一个值：一次 readSymbol 的原文就够把预算吃光）。 */
    static final int MAX_OBSERVATION_CHARS = 4000;

    /** 连续重复几次判为绕圈并主动终止 —— 与手写版同一个值、同一条规则。 */
    static final int MAX_CONSECUTIVE_REPEATS = 3;

    private final ToolRegistry registry;
    private final ToolContext toolContext;
    private final BudgetGuard budget;

    /** 环检测：同一条边不重复走 —— 与手写版同一个实现、同一套判据。 */
    private final VisitedEdgeSet visited = new VisitedEdgeSet();
    private final List<AgentStep> steps = new ArrayList<>();
    /** 证据收集器：工具查到的每一处位置都登记在这里（**不经模型**）。 */
    private final List<AskEvidence> trail = new ArrayList<>();

    /** 上一轮模型在调工具前顺带输出的文字（原生工具调用没有 thought 字段，只能用这个近似"推理"）。 */
    private volatile String lastAssistantText = "";
    private int hop;
    private int repeatedCalls;
    /** 连续重复的次数：连续到上限就判绕圈（一次成功的查询会把它清零）。 */
    private int consecutiveRepeats;

    public SpringAiLoopState(ToolRegistry registry, ToolContext toolContext, BudgetGuard budget) {
        this.registry = registry;
        this.toolContext = toolContext;
        this.budget = budget;
    }

    /**
     * 执行一次工具调用：**环检测 → 执行 → 记轨迹 → 收证据 → 渲染观察**（含"还剩几轮"）。
     */
    public String invoke(String toolName, Map<String, Object> args) {
        String argsKey = args.values().stream().map(String::valueOf).reduce("", (a, b) -> a + b);
        boolean firstVisit = visited.firstVisit(toolName, argsKey);
        hop++;
        if (!firstVisit) {
            consecutiveRepeats++;
            repeatedCalls++;
            budget.recordToolCall(true);
            String note = "这个查询已经做过了（" + toolName + " " + argsKey + "），结果和上次一样。"
                    + "换个方向查，或者用现有材料给结论。";
            // 被拦下的这一跳**也要进轨迹**：界面与报告要能看出"模型在这里被挡了一次"，
            // 否则只看到它忽然换了方向，说不清中间发生过什么（与手写版同一条口径）。
            steps.add(new AgentStep(hop, lastAssistantText, toolName, describeArgs(args), note,
                    List.of(), List.of(), true, 0));
            log.info("[SpringAI] 第 {} 跳：重复调用被环检测拦下 —— {}({})", hop, toolName, argsKey);
            return note;
        }
        consecutiveRepeats = 0;
        budget.recordToolCall(false);

        long t0 = System.nanoTime();
        ToolResult result = registry.execute(toolContext, toolName, args);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        trail.addAll(result.evidence());
        steps.add(new AgentStep(hop, lastAssistantText, toolName, describeArgs(args),
                result.observation(), result.evidence(), result.subjects(), false, ms));
        log.info("[SpringAI] 第 {} 跳：{}({}) → {} 条证据 · {} ms · 已查 {} 条边",
                hop, toolName, argsKey, result.evidence().size(), ms, visited.size());

        return render(result);
    }

    /** 观察 + **把"还剩几轮"告诉模型**（实测：不知道上限的模型会一路查到被掐断）。 */
    private String render(ToolResult result) {
        String observation = clip(result.observation() == null ? "" : result.observation());
        int remaining = budget.remainingRounds();
        String hint = remaining <= 1
                ? "\n\n（**这是最后一轮：请直接用 {\"final\":{...}} 给结论，不要再调工具**）"
                : "\n\n（还能查 " + remaining + " 轮）";
        return observation + hint;
    }

    /**
     * 单条观察的截断：**一次 readSymbol 就能把上下文预算吃光**（手写版同样的 4000 字上限）。
     *
     * <p>它和 {@link PromptCompactionAdvisor} 分工不同：这里管"**新查到的**这一条别太长"，
     * 那里管"**旧的**别一轮轮累积"。
     */
    private static String clip(String observation) {
        if (observation.length() <= MAX_OBSERVATION_CHARS) {
            return observation;
        }
        return observation.substring(0, MAX_OBSERVATION_CHARS)
                + "\n…（本条观察过长，已截断到 " + MAX_OBSERVATION_CHARS + " 字；需要更多就用工具按需再查）";
    }

    private static String describeArgs(Map<String, Object> args) {
        return args.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    /** 供后续轮次的"已做过的查询"提示用（与手写版同一套归一）。 */
    public List<String> visitedLabels(int limit) {
        return visited.labels(limit);
    }

    public void noteAssistantText(String text) {
        if (text != null && !text.isBlank()) {
            this.lastAssistantText = text.strip();
        }
    }

    public List<AgentStep> steps() {
        return steps;
    }

    public List<AskEvidence> trail() {
        return trail;
    }

    public int repeatedCalls() {
        return repeatedCalls;
    }

    /** 连续重复次数（达到 {@link #MAX_CONSECUTIVE_REPEATS} 即判绕圈，由预算管理器终止循环）。 */
    public int consecutiveRepeats() {
        return consecutiveRepeats;
    }
}
