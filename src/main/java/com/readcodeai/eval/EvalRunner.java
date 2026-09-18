package com.readcodeai.eval;

import com.readcodeai.agent.AnswerService;
import com.readcodeai.agent.model.AskAnswer;
import com.readcodeai.eval.model.GeneratedQuestion;
import com.readcodeai.eval.model.QType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 跑评估集：出题 → 逐题作答 → 自动判卷 → 出指标。
 *
 * <h3>⚠️ 这套自动评估覆盖的是什么、不覆盖什么（必须说清，否则命中率会自欺）</h3>
 * 自动生成的题型（定位/调用/调用者/结构/实现）**都是确定性问题**，
 * 标准答案来自索引本身，而这些问题在管线里**根本不走模型**。
 * 所以自动评估测的是**确定性管线的自洽与回归**：
 * 出题 → 路由 → 查询 → 组织答案 → 证据格式化，这一路上信息没被弄丢、没被弄错。
 *
 * <p>**它测不了开放问答的准确率** —— 那部分由人工判定的抽查来量（见验证记录），
 * 两边**分开报**。把两者混成一个"准确率"才是真正会骗人的做法。
 *
 * <p>好处是：跑 200 条只要几秒、不花一分钱 token、而且**同一 seed 完全可复现**。
 */
@Service
public class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);

    private final QuestionGenerator generator;
    private final AutoGrader grader;
    private final AnswerService answerService;

    public EvalRunner(QuestionGenerator generator, AutoGrader grader, AnswerService answerService) {
        this.generator = generator;
        this.grader = grader;
        this.answerService = answerService;
    }

    /** 单题结果。 */
    public record ItemResult(GeneratedQuestion question, boolean refused, boolean failed,
                             AutoGrader.Grade grade, long latencyMs, String answerExcerpt) {
    }

    /** 分题型指标。 */
    public record TypeMetrics(int total, int answered, int refused, int failed,
                              int hit, double hitRate, double avgF1, double avgLatencyMs) {
    }

    public record Report(long repoId, long seed, String generatorVersion, int total,
                         int answered, int refused, int failed, int hit,
                         Map<QType, TypeMetrics> byType, List<ItemResult> items) {

        public double overallHitRate() {
            return total == 0 ? 0 : (double) hit / total;
        }

        public String toReport() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== 评估集实测（seed=").append(seed)
                    .append(" · 生成器 ").append(generatorVersion).append("）===")
                    .append(System.lineSeparator())
                    .append("题目总数 : ").append(total)
                    .append("   给出答案 ").append(answered)
                    .append("   拒答 ").append(refused)
                    .append("   失败 ").append(failed)
                    .append(System.lineSeparator())
                    .append(String.format("整体命中 : %d / %d = %.2f%%%n", hit, total, overallHitRate() * 100))
                    .append(System.lineSeparator())
                    .append(String.format("%-14s %6s %6s %8s %8s %10s%n",
                            "题型", "题数", "命中", "命中率", "平均F1", "平均耗时ms"));
            byType.forEach((type, m) -> sb.append(String.format("%-14s %6d %6d %7.1f%% %8.3f %10.0f%n",
                    type, m.total(), m.hit(), m.hitRate() * 100, m.avgF1(), m.avgLatencyMs())));
            return sb.toString();
        }
    }

    /**
     * @param perType 每种题型多少题（总数 = perType × 5）
     */
    public Report run(long repoId, long seed, int perType) {
        List<GeneratedQuestion> questions = generator.generate(repoId, seed, perType);
        log.info("评估集：{} 道题（seed={}, 每型 {} 道）", questions.size(), seed, perType);

        List<ItemResult> items = new ArrayList<>(questions.size());
        Map<QType, List<ItemResult>> grouped = new EnumMap<>(QType.class);

        for (GeneratedQuestion question : questions) {
            ItemResult result = runOne(repoId, question);
            items.add(result);
            grouped.computeIfAbsent(question.type(), t -> new ArrayList<>()).add(result);
        }

        Map<QType, TypeMetrics> byType = new EnumMap<>(QType.class);
        grouped.forEach((type, results) -> byType.put(type, metricsOf(results)));

        int answered = (int) items.stream().filter(i -> !i.refused() && !i.failed()).count();
        int refused = (int) items.stream().filter(ItemResult::refused).count();
        int failed = (int) items.stream().filter(ItemResult::failed).count();
        int hit = (int) items.stream().filter(i -> i.grade() != null && i.grade().hit()).count();

        Report report = new Report(repoId, seed, QuestionGenerator.GENERATOR_VERSION,
                items.size(), answered, refused, failed, hit, byType, items);
        log.info("评估完成：{}", report.toReport().replace(System.lineSeparator(), " | "));
        return report;
    }

    private ItemResult runOne(long repoId, GeneratedQuestion question) {
        long start = System.nanoTime();
        try {
            AskAnswer answer = answerService.ask(repoId, question.questionText(), null, 8);
            long latency = (System.nanoTime() - start) / 1_000_000;
            if (answer.refused()) {
                return new ItemResult(question, true, false, null, latency,
                        "拒答：" + answer.refusalReason());
            }
            AutoGrader.Grade grade = grader.grade(question, answer);
            String excerpt = answer.answer() == null ? "" : answer.answer();
            return new ItemResult(question, false, false, grade, latency,
                    excerpt.length() <= 120 ? excerpt : excerpt.substring(0, 120) + "...");
        } catch (RuntimeException e) {
            long latency = (System.nanoTime() - start) / 1_000_000;
            return new ItemResult(question, false, true, null, latency,
                    "异常：" + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static TypeMetrics metricsOf(List<ItemResult> results) {
        int total = results.size();
        int answered = (int) results.stream().filter(i -> !i.refused() && !i.failed()).count();
        int refused = (int) results.stream().filter(ItemResult::refused).count();
        int failed = (int) results.stream().filter(ItemResult::failed).count();
        int hit = (int) results.stream().filter(i -> i.grade() != null && i.grade().hit()).count();
        double avgF1 = results.stream().filter(i -> i.grade() != null)
                .mapToDouble(i -> i.grade().f1()).average().orElse(0);
        double avgLatency = results.stream().mapToLong(ItemResult::latencyMs).average().orElse(0);
        return new TypeMetrics(total, answered, refused, failed, hit,
                total == 0 ? 0 : (double) hit / total, avgF1, avgLatency);
    }
}
