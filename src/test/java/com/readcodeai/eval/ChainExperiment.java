package com.readcodeai.eval;

import com.readcodeai.agent.AgentService;
import com.readcodeai.agent.model.AgentAnswer;
import com.readcodeai.agent.model.AgentMode;
import com.readcodeai.agent.model.AskEvidence;
import com.readcodeai.agent.model.StopReason;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 第三组对比实验的**量尺**：同一批链式问题，单跳与多跳各跑一遍，用同一套口径算数。
 *
 * <h3>为什么不能只比"答没答对"</h3>
 * 链式问题的答案是**一个集合**（N 跳内的全部上游方法）。单跳的结构性极限是"只能拿到一层"，
 * 所以只报对错会把"少答了一半"和"完全没答"混为一谈 —— 必须报**召回率**。
 *
 * <h3>为什么两边都要落到「调用点位置」上</h3>
 * 两种跑法给出的证据粒度天然不同：单跳给的是检索到的代码块（一大段），
 * 多跳给的是调用点（一行）。直接比"证据条数"是在比格式，不是比能力。
 * 所以统一换算：**证据位置落在哪个调用点上 → 就说明它找到了那个上游方法**。
 * 换算表由静态分析给出（{@link ChainQuestionGenerator#chainCallSites}），不掺任何人工判断。
 *
 * <h3>三个对照组</h3>
 * <ul>
 *   <li>{@code 单跳(同题)} —— 同一个问题走单跳：反映"单跳对这类问题实际能给什么"</li>
 *   <li>{@code 单跳(最强)} —— 对同一目标问"谁调用了它"（确定性一跳查询）：
 *       **单跳能力对同一个目标的上限**。这一列才是公平的对手，因为它已经是单跳能拿到的最好结果</li>
 *   <li>{@code 多跳} —— 模型自主决定跳向的多轮轨迹</li>
 * </ul>
 */
public final class ChainExperiment {

    /** 一次跑的读数。 */
    public record Run(AgentMode mode, boolean answered, boolean refused, StopReason stopReason,
                      int evidenceCount, int rounds, int toolCalls, int repeatedCalls,
                      long promptTokens, long completionTokens, long latencyMs,
                      int found, int truthSize) {

        public double recall() {
            return truthSize == 0 ? 0 : (double) found / truthSize;
        }
    }

    public record Row(ChainQuestionGenerator.ChainQuestion question, Run singleHopSameQuestion,
                      Run singleHopBest, Run multiHop, List<String> multiHopTrail) {
    }

    public record Summary(int questions, double singleHopBestRecall, double multiHopRecall,
                          double avgHops, double avgRounds, long totalTokens, double avgLatencyMs,
                          int refused) {

        public String toReport() {
            return String.format(
                    "题目 %d 道 · 单跳(最强)平均召回 %.1f%% → 多跳平均召回 %.1f%%%n"
                            + "平均跳数 %.2f · 平均轮次 %.2f · 累计 token %d · 平均耗时 %.0f ms/题 · 拒答 %d 道",
                    questions, singleHopBestRecall * 100, multiHopRecall * 100,
                    avgHops, avgRounds, totalTokens, avgLatencyMs, refused);
        }
    }

    private final AgentService agentService;
    private final ChainQuestionGenerator generator;

    public ChainExperiment(AgentService agentService, ChainQuestionGenerator generator) {
        this.agentService = agentService;
        this.generator = generator;
    }

    public Row run(long repoId, ChainQuestionGenerator.ChainQuestion question) {
        Map<String, Set<String>> callSites =
                generator.chainCallSites(question.target().id(), question.depth());
        Set<String> truth = question.truth();

        // ① 同一个问题走单跳
        AgentAnswer same = agentService.ask(repoId, question.questionText(), AgentMode.SINGLE_HOP, null, 8);
        // ② 最强单跳：对同一目标问「谁调用了它」——确定性一跳查询，答案就是直接调用者
        AgentAnswer best = agentService.ask(repoId, "谁调用了 " + question.target().qualifiedName() + "？",
                AgentMode.SINGLE_HOP, null, 8);
        // ③ 多跳
        AgentAnswer multi = agentService.ask(repoId, question.questionText(), AgentMode.MULTI_HOP, null, 8);

        return new Row(question,
                measure(same, AgentMode.SINGLE_HOP, truth, callSites),
                measure(best, AgentMode.SINGLE_HOP, truth, callSites),
                measure(multi, AgentMode.MULTI_HOP, truth, callSites),
                multi.trailDigest());
    }

    public List<Row> runAll(long repoId, List<ChainQuestionGenerator.ChainQuestion> questions) {
        List<Row> rows = new ArrayList<>();
        for (ChainQuestionGenerator.ChainQuestion question : questions) {
            rows.add(run(repoId, question));
        }
        return rows;
    }

    /** 把一次跑的结果换算成读数：证据位置 → 命中的上游方法 → 召回率。 */
    private static Run measure(AgentAnswer answer, AgentMode mode, Set<String> truth,
                               Map<String, Set<String>> callSites) {
        Set<String> found = new LinkedHashSet<>();
        for (AskEvidence evidence : answer.allEvidence()) {
            Set<String> owners = callSites.get(ChainQuestionGenerator.key(evidence.file(), evidence.startLine()));
            if (owners != null) {
                found.addAll(owners);
            }
        }
        found.retainAll(truth);
        return new Run(mode, !answer.refused(), answer.refused(), answer.stopReason(),
                answer.allEvidence().size(), answer.rounds(), answer.toolCalls(), answer.repeatedCalls(),
                answer.promptTokens(), answer.completionTokens(), answer.latencyMs(),
                found.size(), truth.size());
    }

    public static Summary summarize(List<Row> rows) {
        double singleBest = rows.stream().mapToDouble(row -> row.singleHopBest().recall()).average().orElse(0);
        double multi = rows.stream().mapToDouble(row -> row.multiHop().recall()).average().orElse(0);
        double avgHops = rows.stream().mapToInt(row -> row.multiHop().toolCalls()).average().orElse(0);
        double avgRounds = rows.stream().mapToInt(row -> row.multiHop().rounds()).average().orElse(0);
        long tokens = rows.stream()
                .mapToLong(row -> row.multiHop().promptTokens() + row.multiHop().completionTokens()
                        + row.singleHopSameQuestion().promptTokens() + row.singleHopSameQuestion().completionTokens())
                .sum();
        double avgLatency = rows.stream().mapToLong(row -> row.multiHop().latencyMs()).average().orElse(0);
        int refused = (int) rows.stream().filter(row -> !row.multiHop().answered()).count();
        return new Summary(rows.size(), singleBest, multi, avgHops, avgRounds, tokens, avgLatency, refused);
    }

    public static String table(List<Row> rows) {
        StringBuilder text = new StringBuilder(String.format(
                "%-46s %5s %10s %10s %8s %5s %5s %6s %8s%n",
                "目标方法", "真值", "单跳同题", "单跳最强", "多跳", "跳数", "轮次", "证据", "耗时ms"));
        for (Row row : rows) {
            String target = row.question().target().qualifiedName();
            text.append(String.format("%-46s %5d %11.0f%% %11.0f%% %7.0f%% %5d %5d %6d %8d%n",
                    target.length() <= 46 ? target : target.substring(target.length() - 46),
                    row.question().truthSize(),
                    row.singleHopSameQuestion().recall() * 100,
                    row.singleHopBest().recall() * 100,
                    row.multiHop().recall() * 100,
                    row.multiHop().toolCalls(),
                    row.multiHop().rounds(),
                    row.multiHop().evidenceCount(),
                    row.multiHop().latencyMs()));
        }
        text.append(System.lineSeparator()).append("各跑法的终止原因（诊断用，看不一致时先看这里）：").append(System.lineSeparator());
        for (Row row : rows) {
            text.append(String.format("  %s%n    单跳同题 %s · 证据 %d 条 · 命中 %d/%d%n"
                            + "    单跳最强 %s · 证据 %d 条 · 命中 %d/%d%n"
                            + "    多跳     %s · %d 跳 / %d 轮 · 证据 %d 条 · 命中 %d/%d · prompt %d + completion %d token%n",
                    row.question().target().qualifiedName(),
                    row.singleHopSameQuestion().stopReason(), row.singleHopSameQuestion().evidenceCount(),
                    row.singleHopSameQuestion().found(), row.singleHopSameQuestion().truthSize(),
                    row.singleHopBest().stopReason(), row.singleHopBest().evidenceCount(),
                    row.singleHopBest().found(), row.singleHopBest().truthSize(),
                    row.multiHop().stopReason(), row.multiHop().toolCalls(), row.multiHop().rounds(),
                    row.multiHop().evidenceCount(), row.multiHop().found(), row.multiHop().truthSize(),
                    row.multiHop().promptTokens(), row.multiHop().completionTokens()));
            row.multiHopTrail().forEach(line -> text.append("      ").append(line).append(System.lineSeparator()));
        }
        return text.toString();
    }
}
