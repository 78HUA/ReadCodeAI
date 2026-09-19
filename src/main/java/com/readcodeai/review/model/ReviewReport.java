package com.readcodeai.review.model;

import com.readcodeai.agent.model.AskEvidence;
import com.readcodeai.summary.model.RepoSummary.SymbolRef;

import java.util.List;

/**
 * 一次代码审查的结果。
 *
 * <p>结构上刻意分成两块，和摘要页同一个立场：
 * <ul>
 *   <li>{@link MachineFinding}：**规则算出来的**（方法过长、空 catch、没有人调用、盲区集中…）——
 *       每条都带位置，可复核，不经过模型</li>
 *   <li>{@link Finding}：模型给的意见 —— 每条都必须带证据，而证据要过两道：
 *       真读磁盘对得上 + **确实在给它的材料范围里**</li>
 * </ul>
 *
 * @param dropped 被丢掉的意见条数（证据对不上磁盘、或引用了材料外的位置）。
 *                这个数字必须报出来：审查工具最容易变成"说得很像那么回事"的地方，
 *               丢掉多少条恰恰是它可信度的刻度
 */
public record ReviewReport(
        long repoId,
        String target,
        String targetLocation,
        Material material,
        List<MachineFinding> machineFindings,
        List<Finding> findings,
        int dropped,
        List<String> dropReasons,
        String model,
        boolean llmAvailable,
        String note,
        String caveat,
        int promptTokens,
        int completionTokens,
        long latencyMs) {

    /** 模型的审查意见。{@code grounded=true} 表示它的每条证据都通过了核验。 */
    public record Finding(String severity, String title, String detail, List<AskEvidence> evidence) {

        public boolean grounded() {
            return !evidence.isEmpty();
        }
    }

    /**
     * 规则查出来的问题。{@code rule} 是规则名（便于统计哪条规则最常命中），
     * {@code severity} 用同一套取值，位置必须能在磁盘上找到。
     */
    public record MachineFinding(String rule, String severity, String message,
                                 String file, int startLine, int endLine) {

        public String location() {
            return file + ":" + startLine + "-" + endLine;
        }
    }

    /**
     * 交给模型的材料**清单**（返回给使用者是为了让"审查依据"透明）：
     * 看了哪个类、它的多少方法、谁调用它、它调用了谁、其中多少处未解析。
     *
     * @param sourceLines   类源码的行数（模型看到的是这些行的原文）
     * @param unresolvedCalls 该类对外调用里未解析的条数 —— 静态分析盲区有多大，直接摆出来
     */
    public record Material(
            SymbolRef target,
            int memberCount,
            int callerCount,
            int calleeCount,
            int unresolvedCalls,
            int sourceLines,
            List<String> callerNames) {
    }
}
