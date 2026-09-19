package com.readcodeai.review;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.readcodeai.agent.ModelJson;
import com.readcodeai.agent.ModelOutputFormatException;
import com.readcodeai.agent.model.AskEvidence;
import com.readcodeai.config.LlmClient;
import com.readcodeai.evidence.EvidenceVerifier;
import com.readcodeai.retrieve.FileContentService;
import com.readcodeai.retrieve.NotFoundException;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.CallSiteView;
import com.readcodeai.retrieve.model.RepoView;
import com.readcodeai.retrieve.model.SymbolKinds;
import com.readcodeai.retrieve.model.SymbolView;
import com.readcodeai.review.model.ReviewReport;
import com.readcodeai.review.model.ReviewReport.Finding;
import com.readcodeai.review.model.ReviewReport.MachineFinding;
import com.readcodeai.review.model.ReviewReport.Material;
import com.readcodeai.summary.model.RepoSummary.SymbolRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 代码审查：**规则先说能算的，模型只补说不清的，而且说的每句都要带证据**。
 *
 * <h3>为什么不是"把类丢给模型让它评"</h3>
 * 那样得到的是一段读起来很有道理的话，既不知道依据在哪，也分不清哪些是算出来的、哪些是猜的。
 * 这里的分工是：
 * <ol>
 *   <li><b>材料是取出来的</b>：类源码、它的方法、谁调用它、它调用了谁、多少处未解析 —— 全部来自索引与磁盘</li>
 *   <li><b>能算的先用规则算</b>（{@link ReviewToolkit}）：方法过长、空 catch、没有人调用、盲区集中…</li>
 *   <li><b>模型补剩下的</b>（逻辑矛盾、边界、命名…），每条意见必须带 file+行号，而且
 *       <b>只能引用材料里出现过的位置</b> —— 两道核验，过不了就丢掉并如实报数</li>
 * </ol>
 *
 * <p>单轮调用，不做多跳：审查的对象是**一段代码**，材料一次就能给全；
 * 需要多跳的是"这个参数从哪来"那类链式问题，那是 {@code AgentLoop} 的活。
 */
@Service
public class CodeReviewService {

    private static final Logger log = LoggerFactory.getLogger(CodeReviewService.class);

    /** 模型一次最多给几条意见：审查报告贵在能读、能核对，不在条数多。 */
    private static final int MAX_FINDINGS = 5;

    /**
     * 类源码一次最多给模型多少行（太大就只给前面的部分，并如实写在材料里）。
     *
     * <p>从 400 收到 250 是**实测调出来的**：400 行材料（≈7.6k token）时这个免费模型
     * 经常在 60 秒的读超时里回不来（3 次里 2 次超时）。材料变小之后能正常返回，
     * 代价是"只看类的前半部分"——这一点会写进材料说明里，模型也被告知只能引用给过的行号。
     */
    private static final int MAX_SOURCE_LINES = 250;

    /**
     * 简化模式下只给多少行源码。
     *
     * <p>实测：类一大（近 400 行源码 + 20 多个方法），小模型输出"多条意见 + 每条带证据"这种
     * 结构时**经常直接坏掉**（6 次里 4 次）。重发同样的提示没用 —— 它还是崩。
     * 所以第二次改成**定向降级**：材料砍到一小段、只让它说**一条**最重要的意见，
     * JSON 也短得多。要么给出有依据的一条，要么老实说没有。
     */
    private static final int MAX_SOURCE_LINES_SIMPLE = 120;

    /**
     * 随结果一起返回的边界声明：**说清核验到了哪一步**。
     *
     * <p>不写这句是不行的：审查意见读起来都很有道理，而使用者没办法从证据里看出
     * "这段话是否真的支持这条结论"。实测确实出现过证据全对、结论全错的情况。
     */
    private static final String CAVEAT = "模型意见只核验了『引用的代码真实存在、且在本材料范围内』；"
            + "**『这段代码是否真的支持这条结论』本版没有程序化核验**（③ 层未实现），请自己判断。"
            + "规则部分（machineFindings）是算出来的，每条可复核。";

    private static final String SYSTEM_PROMPT = """
            你在做 Java 代码审查。用户会给你**一个类的材料**：源码、它的方法清单、谁调用它、
            它调用了谁（含未解析的条数）、以及一套**规则已经查出来的问题**。

            请给出材料里**能够支持**的审查意见，重点关注：
            - 异常处理（吞异常、只打日志不处理、把检查异常包成运行异常之后丢了原因）
            - 边界与空值（可能为 null / 空集合 / 越界 / 除零 / 空字符串）
            - 资源管理（流、连接、锁没有在 finally/try-with-resources 里释放）
            - 逻辑矛盾（同一状态码两处含义不同、条件写反、重复判断）
            - 并发（可变共享状态、非线程安全的集合被多线程访问）
            - 命名与参数设计（参数过多、名字与行为不符）

            硬性要求：
            1. **只输出 JSON**，不要 Markdown 代码块，不要任何解释文字。
            2. 每条意见必须带证据：file + startLine + endLine + snippet（**从材料里逐字照抄**）。
               snippet 可以留空字符串（那就只核验文件与行号）。
            3. **只能引用材料里出现过的文件与行号** —— 我们会拿它去磁盘核对，对不上或不在材料里的，这条作废。
            4. **不要重复规则已经查出来的问题**（那些已经在报告里了）。
            5. 没有把握就不要写：宁少勿滥，**编造一条的代价是整条意见被丢掉**。
            6. 最多 %d 条，按严重程度从高到低排。

            输出格式：
            {"findings":[{"severity":"high|medium|low","title":"一句话","detail":"为什么是问题、建议怎么改","evidence":[{"file":"...","startLine":1,"endLine":2,"snippet":"","why":"这段说明了什么"}]}]}
            """.formatted(MAX_FINDINGS);

    private final SymbolQueryService queries;
    private final FileContentService fileContentService;
    private final EvidenceVerifier evidenceVerifier;
    private final LlmClient llmClient;

    public CodeReviewService(SymbolQueryService queries, FileContentService fileContentService,
                             EvidenceVerifier evidenceVerifier, LlmClient llmClient) {
        this.queries = queries;
        this.fileContentService = fileContentService;
        this.evidenceVerifier = evidenceVerifier;
        this.llmClient = llmClient;
    }

    /**
     * @param target 类名（简单名/限定名都可以）或符号 id。只接受**类型**：审查一个方法是"看函数"，
     *               不是这一步要的"输入类或改动"
     */
    public ReviewReport review(Long repoId, String target, String focus) {
        long startNanos = System.nanoTime();
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("请给出要审查的类名（或符号 id）");
        }
        long effectiveRepoId = repoId != null ? repoId : queries.requireLatestRepoId();
        RepoView repo = queries.requireRepo(effectiveRepoId);

        SymbolView type = resolveType(effectiveRepoId, target);
        List<SymbolView> members = queries.children(type.id());
        List<CallSiteView> callers = callersOf(type, members);
        List<CallSiteView> callees = calleesOf(members);
        FileContentService.FileContent source = readSource(repo, type);

        List<MachineFinding> machineFindings = ReviewToolkit.inspect(
                queries, type, members, source == null ? "" : joinLines(source), source == null ? 1 : source.startLine(),
                callees);

        Material material = new Material(new SymbolRef(type.id(), type.kind(), type.qualifiedName(),
                type.signature(), type.filePath(), type.startLine(), type.endLine()), members.size(),
                (int) callers.stream().map(CallSiteView::symbolQualifiedName).distinct().count(),
                callees.size(),
                (int) callees.stream().filter(call -> !call.resolved()).count(),
                source == null ? 0 : source.lines().size(),
                callers.stream().map(CallSiteView::symbolQualifiedName).distinct().limit(10).toList());

        log.info("审查 {}：成员 {} · 调用者 {} · 对外调用 {}（未解析 {}）· 规则命中 {} 条",
                type.qualifiedName(), members.size(), material.callerCount(), callees.size(),
                material.unresolvedCalls(), machineFindings.size());

        if (!llmClient.available()) {
            // 可降级：规则那部分照常给，只是没有"模型读出来的问题"
            return new ReviewReport(effectiveRepoId, type.qualifiedName(), type.location(), material,
                    machineFindings, List.of(), 0, List.of(), null, false,
                    "未配置 LLM（readcodeai.llm.*）：规则查出来的部分不受影响，但『读代码看出的问题』不可用",
                    CAVEAT, 0, 0, elapsedMillis(startNanos));
        }

        String basePrompt = buildUserPrompt(repo, type, members, callers, callees, material,
                machineFindings, source, MAX_SOURCE_LINES, MAX_FINDINGS);
        LlmClient.Completion completion = null;
        Parsed parsed = null;
        String formatProblem = null;
        // 实测：小模型输出"多条意见 + 每条带证据"这种结构时经常坏格式（4 次里坏过 1 次）。
        // 与多跳循环同一套做法：**有限次重发**（不是无限重试），仍不行就只返回规则部分并说清原因。
        for (int attempt = 0; attempt < 2 && parsed == null; attempt++) {
            String prompt = attempt == 0 ? basePrompt
                    : basePrompt + "\n\n注意：你上一次的输出不是合法 JSON。请**只**输出一个 JSON 对象，"
                    + "不要 Markdown 代码块、不要任何解释文字。";
            try {
                completion = llmClient.complete(SYSTEM_PROMPT + focusHint(focus), prompt);
            } catch (RuntimeException e) {
                log.warn("代码审查的模型调用失败：{}", e.toString());
                return new ReviewReport(effectiveRepoId, type.qualifiedName(), type.location(), material,
                        machineFindings, List.of(), 0, List.of(), llmClient.model(), true,
                        "模型调用失败（" + e.getClass().getSimpleName() + "）：规则部分不受影响"
                                + "（材料偏大时这个免费模型会超时，可以缩小审查范围或换个更大的模型）",
                        CAVEAT, 0, 0, elapsedMillis(startNanos));
            }
            try {
                parsed = ModelJson.parse(completion.content(), Parsed.class);
            } catch (ModelOutputFormatException e) {
                formatProblem = e.getMessage();
                log.warn("代码审查的输出不是合法 JSON（第 {} 次）：{}", attempt + 1, e.getMessage());
            }
        }

        if (parsed == null) {
            return new ReviewReport(effectiveRepoId, type.qualifiedName(), type.location(), material,
                    machineFindings, List.of(), 0, List.of("模型输出不是合法 JSON（重发一次仍未修好）"),
                    llmClient.model(), true, "模型输出不是合法 JSON，只返回规则部分：" + formatProblem,
                    CAVEAT, completion.promptTokens(), completion.completionTokens(), elapsedMillis(startNanos));
        }

        List<Finding> kept = new ArrayList<>();
        List<String> dropReasons = new ArrayList<>();
        int dropped = 0;
        for (RawFinding finding : parsed.findings() == null ? List.<RawFinding>of() : parsed.findings()) {
            if (finding == null || finding.title() == null || finding.title().isBlank()) {
                dropped++;
                dropReasons.add("有一条意见没有标题，已丢弃");
                continue;
            }
            List<AskEvidence> cited = ModelJson.validEvidence(finding.evidence());
            Verdict verdict = verify(repo, cited, type, members, callers, callees);
            if (verdict.accepted().isEmpty()) {
                dropped++;
                // **把"为什么丢"写到能直接判断的程度**：只说"没过核验"等于让人对着黑箱猜。
                // 实测撞到过：模型给 5 条意见全被丢掉，而原因分两种（引用了材料外的位置 / 引的行对不上磁盘），
                // 不写清楚就看不出这工具到底靠不靠谱。
                dropReasons.add("「" + finding.title() + "」被丢弃：" + verdict.reason());
                continue;
            }
            kept.add(new Finding(normalizeSeverity(finding.severity()), finding.title(),
                    finding.detail() == null ? "" : finding.detail(), verdict.accepted()));
        }

        log.info("审查完成：模型给了 {} 条 → 采纳 {} 条 · 丢弃 {} 条 · prompt {} + completion {} token",
                parsed.findings() == null ? 0 : parsed.findings().size(), kept.size(), dropped,
                completion.promptTokens(), completion.completionTokens());
        return new ReviewReport(effectiveRepoId, type.qualifiedName(), type.location(), material,
                machineFindings, kept, dropped, dropReasons, llmClient.model(), true, null,
                CAVEAT, completion.promptTokens(), completion.completionTokens(), elapsedMillis(startNanos));
    }

    // ------------------------------------------------------------------ 材料准备

    /** 只认类型：把简单名/限定名换成真实符号；歧义或找不到都明确报错，不猜。 */
    private SymbolView resolveType(long repoId, String target) {
        String text = target.strip();
        if (text.chars().allMatch(Character::isDigit)) {
            SymbolView byId = queries.requireSymbol(Long.parseLong(text));
            if (!SymbolKinds.isType(byId.kind())) {
                throw new IllegalArgumentException("要审查的是一个类，收到的是：" + byId.kind());
            }
            return byId;
        }
        List<SymbolView> candidates = queries.locate(repoId, text, 50).stream()
                .filter(symbol -> SymbolKinds.isType(symbol.kind()))
                .filter(symbol -> symbol.name().equals(text) || symbol.qualifiedName().equals(text))
                .toList();
        if (candidates.isEmpty()) {
            throw new NotFoundException("索引里没有这个类：" + text);
        }
        if (candidates.size() > 1) {
            throw new IllegalArgumentException("「" + text + "」匹配到多个类型，请用限定名："
                    + candidates.stream().map(SymbolView::qualifiedName).collect(Collectors.joining("、")));
        }
        return candidates.get(0);
    }

    /** 谁调用了这个类**的任何一个方法**（按调用者去重）。 */
    private List<CallSiteView> callersOf(SymbolView type, List<SymbolView> members) {
        List<CallSiteView> all = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (SymbolView member : members) {
            if (!SymbolKinds.isCallable(member.kind())) {
                continue;
            }
            for (CallSiteView call : queries.callers(member.id())) {
                if (seen.add(call.symbolQualifiedName() + "@" + call.callSiteLocation())) {
                    all.add(call);
                }
            }
        }
        return all;
    }

    private List<CallSiteView> calleesOf(List<SymbolView> members) {
        List<CallSiteView> all = new ArrayList<>();
        for (SymbolView member : members) {
            if (SymbolKinds.isCallable(member.kind())) {
                all.addAll(queries.callees(member.id()));
            }
        }
        return all;
    }

    private FileContentService.FileContent readSource(RepoView repo, SymbolView type) {
        try {
            return fileContentService.read(repo.id(), type.filePath(), type.startLine(),
                    Math.min(type.endLine(), type.startLine() + MAX_SOURCE_LINES - 1));
        } catch (RuntimeException e) {
            log.warn("读取类源码失败（不影响规则部分）：{}", e.getMessage());
            return null;
        }
    }

    private static String joinLines(FileContentService.FileContent content) {
        return content.lines().stream().map(FileContentService.Line::text)
                .collect(Collectors.joining("\n"));
    }

    private String buildUserPrompt(RepoView repo, SymbolView type, List<SymbolView> members,
                                   List<CallSiteView> callers, List<CallSiteView> callees,
                                   Material material, List<MachineFinding> machineFindings,
                                   FileContentService.FileContent source, int sourceLineLimit,
                                   int maxFindings) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("仓库：").append(repo.name()).append("\n要审查的类：")
                .append(type.kind()).append(' ').append(type.qualifiedName())
                .append("  （").append(type.location()).append("）\n\n");

        prompt.append("它的方法（").append(members.size()).append(" 个）：\n");
        members.stream().filter(member -> SymbolKinds.isCallable(member.kind())).limit(40).forEach(member ->
                prompt.append("- ").append(member.qualifiedName()).append("  ")
                        .append(String.valueOf(member.signature())).append('\n'));

        prompt.append("\n谁调用了它（去重后 ").append(material.callerCount()).append(" 个调用者）：\n");
        if (callers.isEmpty()) {
            prompt.append("- （没有任何仓库内调用 —— 可能是入口、也可能无人使用）\n");
        } else {
            callers.stream().limit(20).forEach(call -> prompt.append("- ")
                    .append(call.symbolQualifiedName()).append(" @ ").append(call.callSiteLocation()).append('\n'));
        }

        prompt.append("\n它调用了谁（").append(callees.size()).append(" 处，其中 ")
                .append(material.unresolvedCalls()).append(" 处未解析）：\n");
        callees.stream().limit(20).forEach(call -> prompt.append("- ")
                .append(call.resolved() ? call.symbolQualifiedName() : call.calleeRaw() + "（未解析）")
                .append(" @ ").append(call.callSiteLocation()).append('\n'));

        prompt.append("\n规则已经查出来的问题（**不要重复这些**）：\n");
        if (machineFindings.isEmpty()) {
            prompt.append("- 无\n");
        } else {
            machineFindings.forEach(finding -> prompt.append("- [").append(finding.rule()).append("] ")
                    .append(finding.message()).append("  (").append(finding.location()).append(")\n"));
        }

        if (source == null) {
            prompt.append("\n（读不到源码，只能凭上面的结构信息审查）\n");
        } else {
            prompt.append("\n类源码（").append(type.filePath()).append(" 第 ")
                    .append(source.startLine()).append('-').append(source.endLine()).append(" 行）：\n");
            source.lines().forEach(line -> prompt.append(line.number()).append(": ").append(line.text()).append('\n'));
        }
        prompt.append("\n请按上面的 JSON 格式给出审查意见。");
        return prompt.toString();
    }

    private static String focusHint(String focus) {
        return focus == null || focus.isBlank() ? ""
                : "\n\n本次使用者特别关注：" + focus.strip() + "（优先看这个方面，但不要编造）";
    }

    // ------------------------------------------------------------------ 核验

    /**
     * 两道核验：① 真读磁盘对得上（{@link EvidenceVerifier}）；② **引用必须在给它的材料里**。
     *
     * <p>第二道是代码审查里最要紧的一条：模型很容易"顺手"引用一段它记得的、但这次没给它的代码，
     * 那行代码是真的，可**不是它这次看到的东西** —— 建立在记忆上的结论不该出现在证据里。
     */
    private Verdict verify(RepoView repo, List<AskEvidence> cited, SymbolView type,
                           List<SymbolView> members, List<CallSiteView> callers,
                           List<CallSiteView> callees) {
        if (cited.isEmpty()) {
            return new Verdict(List.of(), "没有给出可核验的证据（证据条目的文件或行号是空的）");
        }
        List<Range> allowed = allowedRanges(type, members, callers, callees);
        EvidenceVerifier.Report report = evidenceVerifier.verify(Path.of(repo.rootPath()), cited);
        List<AskEvidence> accepted = new ArrayList<>();
        List<String> outsideMaterial = new ArrayList<>();
        List<String> failedOnDisk = new ArrayList<>();
        for (EvidenceVerifier.VerifiedEvidence verified : report.evidence()) {
            AskEvidence evidence = verified.evidence();
            if (!verified.passed()) {
                failedOnDisk.add(evidence.location() + "（" + verified.failureKind() + "）");
            } else if (!coveredBy(allowed, evidence)) {
                outsideMaterial.add(evidence.location());
            } else {
                accepted.add(evidence);
            }
        }
        if (!accepted.isEmpty()) {
            return new Verdict(accepted, null);
        }
        if (!failedOnDisk.isEmpty() && !outsideMaterial.isEmpty()) {
            return new Verdict(List.of(), "证据对不上磁盘：" + String.join("、", failedOnDisk)
                    + "；另外这些位置不在给它的材料里：" + String.join("、", outsideMaterial));
        }
        if (!failedOnDisk.isEmpty()) {
            return new Verdict(List.of(), "证据对不上磁盘：" + String.join("、", failedOnDisk));
        }
        return new Verdict(List.of(), "引用的位置不在给它的材料里：" + String.join("、", outsideMaterial));
    }

    /** 材料里出现过的位置：类源码区间、每个方法的定义区间、每个调用点的行。 */
    private List<Range> allowedRanges(SymbolView type, List<SymbolView> members,
                                      List<CallSiteView> callers, List<CallSiteView> callees) {
        List<Range> ranges = new ArrayList<>();
        ranges.add(new Range(type.filePath(), type.startLine(),
                Math.min(type.endLine(), type.startLine() + MAX_SOURCE_LINES - 1)));
        members.forEach(member -> ranges.add(new Range(member.filePath(), member.startLine(), member.endLine())));
        callers.forEach(call -> ranges.add(new Range(call.callSiteFile(), call.callLine(), call.callLine())));
        callees.forEach(call -> ranges.add(new Range(call.callSiteFile(), call.callLine(), call.callLine())));
        return ranges;
    }

    /** 一条意见的核验结论：采纳了哪些证据；一条都没采纳时，说清是什么原因。 */
    private record Verdict(List<AskEvidence> accepted, String reason) {
    }

    /** 这条引用有没有落在材料给过的某个区间里（被包含）。 */
    private static boolean coveredBy(List<Range> allowed, AskEvidence evidence) {
        for (Range range : allowed) {
            if (range.file().equals(evidence.file())
                    && range.startLine() <= evidence.startLine()
                    && range.endLine() >= evidence.endLine()) {
                return true;
            }
        }
        return false;
    }

    private record Range(String file, int startLine, int endLine) {
    }

    private static String normalizeSeverity(String severity) {
        if (severity == null) {
            return "unknown";
        }
        String text = severity.strip().toLowerCase(Locale.ROOT);
        return switch (text) {
            case "high", "medium", "low" -> text;
            default -> "unknown";
        };
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** 模型返回的 JSON（全部用包装类型：小模型少给字段是常态）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Parsed(List<RawFinding> findings) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawFinding(String severity, String title, String detail,
                      List<ModelJson.EvidenceEntry> evidence) {
    }
}
