package com.readcodeai.eval;

import com.readcodeai.agent.AnswerService;
import com.readcodeai.agent.model.AskAnswer;
import com.readcodeai.eval.model.GeneratedQuestion;
import com.readcodeai.eval.model.QType;
import com.readcodeai.retrieve.VectorRetriever;
import com.readcodeai.retrieve.model.ChunkHit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 第一组对比实验：**纯向量检索 vs 现有三层检索（按题型路由）**。
 *
 * <h3>它想证明什么</h3>
 * 设计文档的原话：「能算准的别猜」。五类评估题的答案都能由符号表/调用图**算出来**，
 * 那么一个纯向量 RAG（把问题向量化、取最像的 8 段代码）在这五类题上能拿到多少真值位置？
 * 拿到这个数字，"为什么不把检索全交给向量"就不再是设计偏好，而是实测结论。
 *
 * <h3>判卷口径 —— 与答案级判卷刻意不同，且必须说清</h3>
 * 答案级（{@link AutoGrader}）考"引用的位置键对不对"；这里是**检索级**：
 * 真值键（{@code 文件:行}）被某条命中 chunk 的行区间**覆盖**就算召回 ——
 * 因为检索层的职责是"把相关代码取回来"，调用点在方法体第 37 行，而 chunk 从方法声明行开始，
 * 取回了这个 chunk 就是检索成功了。用答案级的精确键去判检索层，会把正确的检索错判成失败。
 *
 * <h3>双方是谁</h3>
 * <ul>
 *   <li><b>A 方（纯向量）</b>：题面 → embedding → 全 chunk 余弦 top-8 → 覆盖的真值位置</li>
 *   <li><b>B 方（现有路由）</b>：五类题都命中确定性路由，位置由符号表/调用图算出 ——
 *       召回按构造是 1.0，它在这张表里是**量尺自检**（若它不到 1.0，说明判卷或路由出了问题）</li>
 * </ul>
 */
public class VectorComparison {

    /** 与问答管线现用的 top-k 同口径（retrieve.top-k=8）：对比要站在同一尺度上。 */
    public static final int TOP_K = 8;

    private final AnswerService answerService;
    private final VectorRetriever vectorRetriever;

    public VectorComparison(AnswerService answerService, VectorRetriever vectorRetriever) {
        this.answerService = answerService;
        this.vectorRetriever = vectorRetriever;
    }

    public record Row(QType type, String question, int truthCount,
                      double vectorRecall, double vectorPrecision, double vectorF1,
                      double routeRecall, long vectorMillis, long routeMillis) {
    }

    /** 按题型聚合的平均值。 */
    public record TypeSummary(QType type, int count, double recall, double precision, double f1,
                              double routeRecall, long vectorMillis, long routeMillis) {
    }

    public record Summary(List<TypeSummary> perType, double overallRecall, double overallF1) {
    }

    public record Result(List<Row> rows, Summary summary) {
    }

    public Result run(long repoId, List<GeneratedQuestion> questions) {
        List<Row> rows = new ArrayList<>(questions.size());
        for (GeneratedQuestion question : questions) {
            rows.add(runOne(repoId, question));
        }
        return new Result(rows, summarize(rows));
    }

    Row runOne(long repoId, GeneratedQuestion question) {
        // A 方：纯向量
        long vectorStart = System.nanoTime();
        List<ChunkHit> hits = vectorRetriever.search(repoId, question.questionText(), TOP_K);
        long vectorMillis = (System.nanoTime() - vectorStart) / 1_000_000;
        Set<String> truth = new LinkedHashSet<>(question.truthKeys());
        double recall = recallBySpans(hits, truth);
        double precision = precisionBySpans(hits, truth);
        double f1 = (precision + recall) == 0 ? 0 : 2 * precision * recall / (precision + recall);

        // B 方：现有路由（确定性题型不经模型，位置由符号表/调用图算出）
        long routeStart = System.nanoTime();
        // 同样是"我们自己跑的题"：走真实流水线，但流水里标成 EVAL（不计入用户问答统计）
        AskAnswer answer = answerService.askForEval(repoId, question.questionText(), TOP_K);
        long routeMillis = (System.nanoTime() - routeStart) / 1_000_000;
        AutoGrader.Grade routeGrade = AutoGrader.gradeKeys(question, AutoGrader.claimedKeys(answer));

        return new Row(question.type(), question.questionText(), truth.size(),
                recall, precision, f1, routeGrade.recall(), vectorMillis, routeMillis);
    }

    /** 检索级召回：真值键被某条命中 chunk 的行区间**覆盖**的比例（主实验与中文子实验共用）。 */
    public static double recallBySpans(List<ChunkHit> hits, Set<String> truthKeys) {
        if (truthKeys.isEmpty()) {
            return 0;
        }
        long covered = truthKeys.stream().filter(key -> hits.stream().anyMatch(hit -> spans(hit, key))).count();
        return (double) covered / truthKeys.size();
    }

    /** 检索级精确率：命中里有多少条至少覆盖了一个真值键。 */
    public static double precisionBySpans(List<ChunkHit> hits, Set<String> truthKeys) {
        if (hits.isEmpty()) {
            return 0;
        }
        long useful = hits.stream().filter(hit -> truthKeys.stream().anyMatch(key -> spans(hit, key))).count();
        return (double) useful / hits.size();
    }

    public Summary summarize(List<Row> rows) {
        Map<QType, List<Row>> byType = new LinkedHashMap<>();
        for (Row row : rows) {
            byType.computeIfAbsent(row.type(), type -> new ArrayList<>()).add(row);
        }
        List<TypeSummary> perType = new ArrayList<>();
        for (Map.Entry<QType, List<Row>> entry : byType.entrySet()) {
            List<Row> same = entry.getValue();
            perType.add(new TypeSummary(entry.getKey(), same.size(),
                    average(same, Row::vectorRecall), average(same, Row::vectorPrecision),
                    average(same, Row::vectorF1), average(same, Row::routeRecall),
                    (long) average(same, Row::vectorMillis), (long) average(same, Row::routeMillis)));
        }
        return new Summary(perType, average(rows, Row::vectorRecall), average(rows, Row::vectorF1));
    }

    public static String table(Result result) {
        StringBuilder text = new StringBuilder();
        text.append(String.format("%-12s %5s %10s %10s %10s %10s %12s %12s%n",
                "题型", "题数", "向量召回", "向量精确率", "向量F1", "路由召回", "向量耗时", "路由耗时"));
        for (TypeSummary type : result.summary().perType()) {
            text.append(String.format("%-12s %5d %9.1f%% %9.1f%% %9.1f%% %9.1f%% %10d ms %10d ms%n",
                    type.type(), type.count(), type.recall() * 100, type.precision() * 100,
                    type.f1() * 100, type.routeRecall() * 100, type.vectorMillis(), type.routeMillis()));
        }
        text.append(String.format("%n整体：纯向量 RAG 在这五类题上的召回上限 = %.1f%%（F1 %.1f%%）%n",
                result.summary().overallRecall() * 100, result.summary().overallF1() * 100));
        return text.toString();
    }

    private static double average(List<Row> rows, java.util.function.ToDoubleFunction<Row> metric) {
        if (rows.isEmpty()) {
            return 0;
        }
        return rows.stream().mapToDouble(metric).average().orElse(0);
    }

    /**
     * 真值键（文件:行）是否落在命中 chunk 的行区间里。
     * 取 lastIndexOf(':') 是为了容忍绝对路径里的盘符冒号（虽然库里存的是相对路径）。
     */
    static boolean spans(ChunkHit hit, String truthKey) {
        int colon = truthKey.lastIndexOf(':');
        if (colon <= 0 || colon == truthKey.length() - 1) {
            return false;
        }
        String file = truthKey.substring(0, colon);
        int line;
        try {
            line = Integer.parseInt(truthKey.substring(colon + 1));
        } catch (NumberFormatException e) {
            return false;
        }
        return hit.filePath().equals(file) && hit.startLine() <= line && line <= hit.endLine();
    }
}
