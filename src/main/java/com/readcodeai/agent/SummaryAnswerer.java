package com.readcodeai.agent;

import com.readcodeai.agent.model.AgentAnswer;
import com.readcodeai.agent.model.AgentMode;
import com.readcodeai.agent.model.AnsweredBy;
import com.readcodeai.agent.model.AskEvidence;
import com.readcodeai.agent.model.StopReason;
import com.readcodeai.agent.model.VerificationSummary;
import com.readcodeai.evidence.EvidenceVerifier;
import com.readcodeai.summary.RepoSummaryService;
import com.readcodeai.summary.model.RepoSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 总结类问题的作答：**不是检索，而是"读摘要"**。
 *
 * <h3>为什么这条路的答案可以信</h3>
 * 摘要把「结构」与「语义」分得很开：模块划分、入口类、调用枢纽都是**查库算出来的硬事实**，
 * 每个都带文件与行号；模型只负责把它们讲成一句话，而且它提到的符号名会被反过来在索引里核对。
 * 所以这里可以给出**与检索路线同一套的证据**（文件 + 行号），而不是"模型说了一段话"。
 *
 * <h3>口径（要写进答案里，不能只在文档里）</h3>
 * <ul>
 *   <li>数字与结构 = 算出来的（索引）</li>
 *   <li>"它做什么 / 主要功能" = 模型组织的（材料是索引给的）</li>
 *   <li>没配模型时**只给结构**并如实说明 —— 于是这条路线在降级状态下仍然可用</li>
 * </ul>
 *
 * <p>证据过的是 ① 层核验（文件与行号有效性），与静态路线一致：片段是空的（没有 snippet），
 * 所以不涉及"内容比对"。③ 层判定不做 —— 答案不是"模型从片段里得出的结论"，没有"张冠李戴"可言。
 */
@Component
public class SummaryAnswerer {

    private static final Logger log = LoggerFactory.getLogger(SummaryAnswerer.class);

    /** 证据条数上限：摘要里可以点开的东西很多，但"一屏能看完"比"全都堆上"有用。 */
    private static final int MAX_EVIDENCE = 10;
    private static final int MAX_MODULES_SHOWN = 8;

    private final RepoSummaryService summaryService;
    private final EvidenceVerifier evidenceVerifier;

    public SummaryAnswerer(RepoSummaryService summaryService, EvidenceVerifier evidenceVerifier) {
        this.summaryService = summaryService;
        this.evidenceVerifier = evidenceVerifier;
    }

    public AgentAnswer answer(long repoId, Path repoRoot, String question, AgentMode mode) {
        long startNanos = System.nanoTime();
        RepoSummary summary = summaryService.summarize(repoId, true);

        List<AskEvidence> candidates = evidenceOf(summary);
        EvidenceVerifier.Report report = evidenceVerifier.verify(repoRoot, candidates);
        List<AskEvidence> accepted = report.evidence().stream()
                .filter(EvidenceVerifier.VerifiedEvidence::passed)
                .map(EvidenceVerifier.VerifiedEvidence::evidence)
                .toList();
        if (accepted.size() < candidates.size()) {
            log.warn("摘要路线的证据有 {} 条未通过核验（索引可能已过期）", candidates.size() - accepted.size());
        }

        RepoSummary.Semantics semantics = summary.semantics();
        boolean withSemantics = semantics != null && semantics.available();
        // 这次真的生成了语义才算这次的 token；缓存命中时这份说明是"当初花的"（与答案缓存同一口径）
        boolean generated = withSemantics && !semantics.cached();

        return new AgentAnswer(compose(summary), accepted, false, null,
                withSemantics ? AnsweredBy.LLM : AnsweredBy.STATIC,
                mode, List.of(), 0, 0, 0, StopReason.SUMMARY_ANSWERED,
                generated ? semantics.promptTokens() : 0,
                generated ? semantics.completionTokens() : 0,
                0, elapsedMillis(startNanos),
                VerificationSummary.of(accepted.size(), report.failed(), List.of(),
                        "摘要路线：结构由索引算出，语义由模型组织（它提到的符号已回索引核对）"),
                false, null);
    }

    /** 答案文本：先说结论（它做什么），再说规模与模块，最后把口径写清楚。 */
    private static String compose(RepoSummary summary) {
        RepoSummary.Structure structure = summary.structure();
        RepoSummary.Scale scale = structure.scale();
        RepoSummary.Semantics semantics = summary.semantics();
        StringBuilder text = new StringBuilder();

        text.append("【").append(summary.repoName()).append("】")
                .append(scale.fileCount()).append(" 个文件 · ")
                .append(scale.totalLoc()).append(" 行 · ")
                .append(scale.totalSymbols()).append(" 个符号（")
                .append(scale.classCount()).append(" 类 / ")
                .append(scale.interfaceCount()).append(" 接口 / ")
                .append(scale.methodCount()).append(" 方法）\n");

        if (semantics != null && semantics.available() && semantics.overview() != null) {
            text.append("\n它做什么：").append(semantics.overview().text()).append("\n");
            List<RepoSummary.ProjectNote> features = semantics.features();
            if (features != null && !features.isEmpty()) {
                text.append("\n主要功能：\n");
                features.forEach(feature -> text.append("  · ").append(feature.text()).append("\n"));
            }
        } else {
            // 降级也要把话说清楚：**没配模型不是"没有答案"，只是少了一句总结**
            text.append("\n（语义说明未生成：")
                    .append(semantics == null ? "摘要里没有语义部分" : semantics.reason())
                    .append(" —— 下面是索引算出来的结构，照常可用）\n");
        }

        List<RepoSummary.Module> modules = structure.modules();
        if (modules != null && !modules.isEmpty()) {
            text.append("\n模块划分（共 ").append(modules.size()).append(" 个）：\n");
            modules.stream().limit(MAX_MODULES_SHOWN).forEach(module ->
                    text.append("  · ").append(module.name())
                            .append("：").append(module.fileCount()).append(" 文件 / ")
                            .append(module.symbolCount()).append(" 符号\n"));
            if (modules.size() > MAX_MODULES_SHOWN) {
                text.append("  · …另有 ").append(modules.size() - MAX_MODULES_SHOWN).append(" 个模块\n");
            }
        }

        text.append("\n结构要点：");
        if (structure.entryPoints() != null && !structure.entryPoints().isEmpty()) {
            text.append("入口 ")
                    .append(structure.entryPoints().stream().limit(3)
                            .map(RepoSummary.SymbolRef::qualifiedName)
                            .reduce((a, b) -> a + "、" + b).orElse(""));
            text.append("；");
        }
        if (structure.callHubs() != null && !structure.callHubs().isEmpty()) {
            RepoSummary.Ranked hub = structure.callHubs().get(0);
            text.append("被调用最多的是 ").append(hub.symbol().qualifiedName())
                    .append("（").append(hub.count()).append(" 处调用）；");
        }
        text.append("调用解析率 ")
                .append(String.format("%.1f%%", scale.callResolveRate() * 100))
                .append("（未解析的多为外部依赖，见概览页）。");

        text.append("\n\n（口径：规模、模块划分、入口与枢纽都由索引算出，可以点开证据核对；");
        text.append(semantics != null && semantics.available()
                ? "「它做什么 / 主要功能」是模型依据这些材料组织的，它提到的符号名已回索引核对。"
                : "语义说明这次没有生成（未配置模型），所以这段没有模型参与。");
        text.append("）");
        return text.toString();
    }

    /**
     * 从摘要的结构里取证据：**每个都是带文件行号的真实符号**，所以这条路线的证据同样可核对。
     *
     * <p>取的是"最像入口 / 最像枢纽 / 各模块的代表类型"三类 —— 它们正是"这个项目是什么"的主要线索。
     */
    private static List<AskEvidence> evidenceOf(RepoSummary summary) {
        RepoSummary.Structure structure = summary.structure();
        Map<String, AskEvidence> unique = new LinkedHashMap<>();

        addAll(unique, structure.entryPoints(), 3, ref -> "入口类：" + ref.qualifiedName());
        addAll(unique, structure.callHubs() == null ? null
                        : structure.callHubs().stream().map(RepoSummary.Ranked::symbol).toList(),
                3, ref -> "调用枢纽：" + ref.qualifiedName());
        if (structure.modules() != null) {
            for (RepoSummary.Module module : structure.modules()) {
                if (module.keyTypes() == null || module.keyTypes().isEmpty()) {
                    continue;
                }
                RepoSummary.SymbolRef ref = module.keyTypes().get(0);
                unique.putIfAbsent(ref.location(),
                        new AskEvidence(ref.filePath(), ref.startLine(), ref.endLine(), "",
                                module.name() + " 模块的代表类型：" + ref.qualifiedName()));
                if (unique.size() >= MAX_EVIDENCE) {
                    break;
                }
            }
        }
        return new ArrayList<>(unique.values()).subList(0, Math.min(unique.size(), MAX_EVIDENCE));
    }

    private static void addAll(Map<String, AskEvidence> target, List<RepoSummary.SymbolRef> refs,
                               int limit, java.util.function.Function<RepoSummary.SymbolRef, String> why) {
        if (refs == null) {
            return;
        }
        refs.stream().limit(limit).forEach(ref -> target.putIfAbsent(ref.location(),
                new AskEvidence(ref.filePath(), ref.startLine(), ref.endLine(), "", why.apply(ref))));
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
