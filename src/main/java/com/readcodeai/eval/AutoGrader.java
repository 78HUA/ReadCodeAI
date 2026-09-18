package com.readcodeai.eval;

import com.readcodeai.agent.model.AskAnswer;
import com.readcodeai.agent.model.AskEvidence;
import com.readcodeai.eval.model.GeneratedQuestion;
import com.readcodeai.eval.model.QType;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 自动判卷：**拿答案引用的位置，和静态分析算出的标准答案比**。
 *
 * <p>不需要人工标注，也不需要另一个模型来打分 —— 这是整套评估可信的前提。
 *
 * <p>判卷口径（统一成位置键 {@code 文件:起始行}，全项目一种格式）：
 * <ul>
 *   <li><b>LOCATE</b>：答案必须**包含**目标位置（答案里多列出同名符号是对的，不算错）</li>
 *   <li>其余题型：**集合比较**，算精确率 / 召回率 / F1；多报和少报都要扣分</li>
 * </ul>
 *
 * <p>为什么要算 F1 而不是只看"命中/不命中"：集合适问题里"多报一个"和"少报一个"
 * 是两种不同的错误，只报命中率会把它们混在一起，看不出模型是保守还是激进。
 */
@Component
public class AutoGrader {

    public record Grade(boolean hit, double precision, double recall, double f1, String note) {

        public static Grade missed(String note) {
            return new Grade(false, 0, 0, 0, note);
        }
    }

    public Grade grade(GeneratedQuestion question, AskAnswer answer) {
        Set<String> truth = new LinkedHashSet<>(question.truthKeys());
        if (truth.isEmpty()) {
            return Grade.missed("题目没有标准答案（不该出现）");
        }

        Set<String> claimed = new LinkedHashSet<>();
        for (AskEvidence evidence : answer.evidence()) {
            claimed.add(QuestionGenerator.key(evidence.file(), evidence.startLine()));
        }

        if (question.type() == QType.LOCATE) {
            // 定位题：答案里**包含**正确答案即可 —— 同名符号存在时多列出来是正确行为
            boolean hit = claimed.containsAll(truth);
            return hit
                    ? new Grade(true, 1, 1, 1, "命中")
                    : Grade.missed("未包含目标位置，期望其一：" + truth);
        }

        long truePositive = claimed.stream().filter(truth::contains).count();
        double precision = claimed.isEmpty() ? 0 : (double) truePositive / claimed.size();
        double recall = (double) truePositive / truth.size();
        double f1 = (precision + recall) == 0 ? 0 : 2 * precision * recall / (precision + recall);
        boolean hit = f1 >= 0.999;

        String note = hit ? "集合完全一致"
                : "多报 %d 个、漏报 %d 个".formatted(claimed.size() - truePositive, truth.size() - truePositive);
        return new Grade(hit, precision, recall, f1, note);
    }

    /** 供指标统计用：把答案引用的位置抽出来（重载给测试用）。 */
    public static Set<String> claimedKeys(AskAnswer answer) {
        Set<String> keys = new HashSet<>();
        for (AskEvidence evidence : answer.evidence()) {
            keys.add(QuestionGenerator.key(evidence.file(), evidence.startLine()));
        }
        return keys;
    }
}
