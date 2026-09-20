package com.readcodeai.agent;

import com.readcodeai.agent.log.AnswerLogService;
import com.readcodeai.agent.log.AnswerLogSource;
import com.readcodeai.agent.model.AnsweredBy;
import com.readcodeai.agent.model.AskAnswer;
import com.readcodeai.agent.model.AskEvidence;
import com.readcodeai.agent.model.SupportCheck;
import com.readcodeai.agent.model.VerificationSummary;
import com.readcodeai.config.LlmClient;
import com.readcodeai.config.ReadCodeAiProperties;
import com.readcodeai.evidence.EvidenceRepair;
import com.readcodeai.evidence.EvidenceVerifier;
import com.readcodeai.evidence.SupportChecker;
import com.readcodeai.retrieve.ContextSelector;
import com.readcodeai.retrieve.QueryRouter;
import com.readcodeai.retrieve.SymbolLookups;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.TextRetriever;
import com.readcodeai.retrieve.model.CallSiteView;
import com.readcodeai.retrieve.model.ChunkHit;
import com.readcodeai.retrieve.model.SymbolView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 问答流水线：**检索 → 模型组织答案 → 输出必须带证据**。
 *
 * <p>模型在这里的责任被刻意压到最小：**它不负责找代码，只负责把已经找到的片段讲清楚**。
 * 代码是第 2 层全文检索按标识符找出来的（确定性检索），模型拿到的每个片段都带着
 * 「文件 + 起止行」的标注，它只能引用这些标注，不许自己编。
 *
 * <p><b>本阶段只做结构约束</b>（必须有证据、必须带 file+line）。
 * **内容核验**——真去读磁盘比对行号与片段是否属实——是第 4 步的事，
 * 那一步才是「玩具 vs 工具」的分界。
 */
@Service
public class AnswerService {

    private static final Logger log = LoggerFactory.getLogger(AnswerService.class);

    /** 检索候选池 = 最终选用块数 × 这个倍数：先多召回，再让选片按去重/多样性/预算决定留哪些。 */
    private static final int CANDIDATE_MULTIPLIER = 4;

    private static final String SYSTEM_PROMPT = """
            你是代码库理解助手。只能依据下面提供的代码片段回答问题。

            硬性要求：
            1. 每条结论都必须给出证据：文件路径 + 起止行号，且必须与片段标注里的完全一致，不许自己编造。
            2. snippet 必须是从片段里**逐字照抄**的那几行原文（含缩进不必完全一致，但内容不能改写）。
               我们会拿它去磁盘上核对 —— **对不上，这条证据就作废**。
            3. 如果提供的片段不足以回答，就把 refused 设为 true 并在 refusalReason 里说明缺什么。不要猜测。
            4. 只输出 JSON，不要 Markdown 代码块，不要任何解释性文字。
            5. answer 用中文、简洁，不要复述代码。

            输出格式：
            {"answer":"结论","evidence":[{"file":"片段里的文件路径","startLine":1,"endLine":2,"snippet":"那几行的原文","why":"这段代码说明了什么"}],"refused":false,"refusalReason":""}
            """;

    private final TextRetriever textRetriever;
    private final SymbolQueryService symbolQueryService;
    private final QueryRouter queryRouter;
    private final ContextSelector contextSelector;
    private final EvidenceVerifier evidenceVerifier;
    private final EvidenceRepair evidenceRepair;
    private final SupportChecker supportChecker;
    private final LlmClient llmClient;
    private final ReadCodeAiProperties properties;
    private final AnswerLogService answerLogService;

    public AnswerService(TextRetriever textRetriever,
                         SymbolQueryService symbolQueryService,
                         QueryRouter queryRouter,
                         ContextSelector contextSelector,
                         EvidenceVerifier evidenceVerifier,
                         EvidenceRepair evidenceRepair,
                         SupportChecker supportChecker,
                         LlmClient llmClient,
                         ReadCodeAiProperties properties,
                         AnswerLogService answerLogService) {
        this.textRetriever = textRetriever;
        this.symbolQueryService = symbolQueryService;
        this.queryRouter = queryRouter;
        this.contextSelector = contextSelector;
        this.evidenceVerifier = evidenceVerifier;
        this.evidenceRepair = evidenceRepair;
        this.supportChecker = supportChecker;
        this.llmClient = llmClient;
        this.properties = properties;
        this.answerLogService = answerLogService;
    }

    /**
     * @param scopePath 可选：把检索范围限制在某个文件/目录（路径包含匹配）。第 2 步先支持单文件范围，
     *                  缩小问题空间便于定位问题。
     */
    public AskAnswer ask(Long repoId, String question, String scopePath, Integer topK) {
        return ask(repoId, question, scopePath, topK, AnswerLogSource.USER);
    }

    /**
     * 评估集 / 对比实验的跑题：**同一条问答流水线**，只在流水里标成 {@code EVAL}。
     *
     * <p>为什么不另走一条简化路径：那样评估测的就不是真实的问答流程了。
     * 又不愿意不记账：跑一次评估就是 200+ 行，页面上的"累计问答"会被它撑起来。
     * 标出来即可两全 —— 两块账分开算（见 {@code AnswerLogStats}）。
     */
    public AskAnswer askForEval(Long repoId, String question, Integer topK) {
        return ask(repoId, question, null, topK, AnswerLogSource.EVAL);
    }

    private AskAnswer ask(Long repoId, String question, String scopePath, Integer topK,
                          AnswerLogSource source) {
        long startNanos = System.nanoTime();
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }

        long effectiveRepoId = repoId != null ? repoId : symbolQueryService.requireLatestRepoId();
        // 证据校验要拿仓库根路径把「相对路径」还原成磁盘上的真实文件，所以提前取
        Path repoRoot = Path.of(symbolQueryService.requireRepo(effectiveRepoId).rootPath());

        // 先路由：**确定性问题根本不叫模型**。「谁调用了 X」的答案是调用图算出来的，
        // 让模型去"组织"它，等于把确定性换成不确定性。
        QueryRouter.Routed routed = queryRouter.route(effectiveRepoId, question,
                SymbolLookups.of(symbolQueryService, effectiveRepoId));

        AskAnswer answer;
        String mode;
        if (routed.isDeterministic()) {
            log.info("问题走确定性路线：route={} targets={}", routed.route(), routed.targets().size());
            mode = "STATIC";
            answer = answerDeterministically(routed, repoRoot, elapsedMillis(startNanos));
        } else {
            mode = "SINGLE_HOP";
            answer = answerSemantically(effectiveRepoId, question, scopePath, topK, repoRoot, startNanos);
        }

        // 记账放在**唯一的出口**上：静态 / 语义 / 检索为空 / 模型故障 / 各种拒答，
        // 出来的都是上面两次调用的返回值 —— 只写这一处，才不会出现"某条路忘了记"。
        answerLogService.recordAsk(effectiveRepoId, null, source, question, mode,
                AnswerLogService.routeJson(routed), answer);
        return answer;
    }

    /**
     * 语义路线（单跳）：检索 → 模型组织语言 → 证据核验 → 必要时定向修正。
     *
     * <p>与确定性路线的分界在上面的 {@link #ask}：走到这里，说明问题没被路由成
     * 「查表就能答」的那几类，必须靠检索 + 模型。
     */
    private AskAnswer answerSemantically(long repoId, String question, String scopePath, Integer topK,
                                         Path repoRoot, long startNanos) {
        // 没配模型就按降级处理（静态分析那几层照常可用）
        if (!llmClient.available()) {
            throw new LlmUnavailableException("未配置 LLM（readcodeai.llm.*），语义问答不可用；"
                    + "定位 / 调用关系 / 实现类 / 全文检索等确定性能力不受影响");
        }

        int limit = topK != null && topK > 0 ? topK : properties.getRetrieve().getTopK();
        // 先多召回、再由选片决定留哪些：固定只取 8 条时，去重/多样性/预算这些规则根本轮不到生效
        List<ChunkHit> hits = textRetriever.search(repoId, question, limit * CANDIDATE_MULTIPLIER);
        if (scopePath != null && !scopePath.isBlank()) {
            String scope = scopePath.replace('\\', '/');
            hits = hits.stream().filter(hit -> hit.filePath().contains(scope)).toList();
        }

        // 选片：检索回来的块不等于喂给模型的块（去重 / 单文件上限 / 精确名提升 / 预算截断）
        ContextSelector.Selection selection = contextSelector.select(question, hits,
                properties.getRetrieve().getMaxContextTokens(), limit,
                properties.getRetrieve().getMaxChunksPerFile());
        List<ChunkHit> selected = selection.chunks();
        List<String> retrievedFrom = selected.stream().map(ChunkHit::location).toList();
        log.info("上下文选片：检索 {} 段 → 采用 {} 段（{} tokens）· 未采用 {} 段",
                hits.size(), selected.size(), selection.totalTokens(), selection.droppedReasons().size());
        if (!selection.droppedReasons().isEmpty()) {
            log.debug("未采用的原因（前 5 条）：{}", selection.droppedReasons().stream().limit(5).toList());
        }

        if (selected.isEmpty()) {
            return AskAnswer.refused("在索引里没有检索到与该问题相关的代码片段",
                    retrievedFrom, 0, elapsedMillis(startNanos));
        }

        LlmClient.Completion completion;
        try {
            completion = llmClient.complete(SYSTEM_PROMPT, buildUserPrompt(question, selected));
        } catch (RuntimeException e) {
            // 调用故障与"片段里没有答案"必须分开报 —— 否则使用者会把网络问题当成分析结论。
            // 多跳那条路一开始就是这么处理的（StopReason.LLM_CALL_FAILED），这里补齐：
            // 实测撞到过 60 秒读超时（一次测试里连着调了很多次之后），单跳当时是把异常抛成了 500。
            log.warn("单跳模型调用失败：{}", e.toString());
            return new AskAnswer(null, List.of(), true,
                    "模型调用失败（" + e.getClass().getSimpleName() + "：" + e.getMessage()
                            + "）。这是调用故障，不是「仓库里没有答案」。",
                    AnsweredBy.LLM, retrievedFrom, hits.size(), selected.size(), 0, 0,
                    elapsedMillis(startNanos), VerificationSummary.none());
        }
        ModelJson.SingleHopAnswer parsed;
        try {
            parsed = ModelJson.parse(completion.content(), ModelJson.SingleHopAnswer.class);
        } catch (ModelOutputFormatException e) {
            // 模型输出不是合法 JSON：**不崩，也不假装答出来了** —— 明确归类并拒答。
            // 这类失败是可以被计数的质量指标（格式错误率），不是异常。
            log.warn("模型输出格式错误，按拒答处理：{} · 原文前 200 字：{}",
                    e.getMessage(), abbreviate(e.rawOutput()));
            return new AskAnswer(null, List.of(), true,
                    "模型输出不是合法 JSON，本次未能给出带证据的答案：" + e.getMessage(),
                    AnsweredBy.LLM, retrievedFrom, hits.size(), selected.size(),
                    completion.promptTokens(), completion.completionTokens(), elapsedMillis(startNanos),
                    VerificationSummary.none());
        }

        if (Boolean.TRUE.equals(parsed.refused())) {
            return new AskAnswer(null, List.of(), true,
                    parsed.refusalReason() == null || parsed.refusalReason().isBlank()
                            ? "模型判断给定片段不足以回答" : parsed.refusalReason(),
                    AnsweredBy.LLM, retrievedFrom, hits.size(), selected.size(),
                    completion.promptTokens(), completion.completionTokens(), elapsedMillis(startNanos),
                    VerificationSummary.none());
        }

        // 解析宽容、校验严格：小模型常给 null 或缺字段，容错是为了不整条失败；
        // 但缺字段的证据必须被丢掉 —— 放出去就等于拿脏数据当证据。
        List<AskEvidence> evidence = ModelJson.validEvidence(parsed.evidence());
        if (evidence.size() < (parsed.evidence() == null ? 0 : parsed.evidence().size())) {
            log.warn("模型给出的证据里有 {} 条缺文件或行号，已丢弃",
                    parsed.evidence().size() - evidence.size());
        }

        // ---- 分水岭：真读磁盘核验。不过就定向修，修不了就丢；全丢光就拒答 ----
        EvidenceVerifier.Report firstPass = evidenceVerifier.verify(repoRoot, evidence);
        List<AskEvidence> accepted = firstPass.evidence().stream()
                .filter(EvidenceVerifier.VerifiedEvidence::passed)
                .map(EvidenceVerifier.VerifiedEvidence::evidence)
                .collect(Collectors.toCollection(ArrayList::new));
        List<String> repairs = new ArrayList<>();

        if (firstPass.failed() > 0) {
            EvidenceRepair.Result repaired = evidenceRepair.repair(firstPass, selected);
            repairs.addAll(repaired.actions());
            if (!repaired.candidates().isEmpty()) {
                // 修正过的候选**必须再过一遍校验** —— 不能因为"我修过了"就当成通过
                EvidenceVerifier.Report secondPass = evidenceVerifier.verify(repoRoot, repaired.candidates());
                secondPass.evidence().stream()
                        .filter(EvidenceVerifier.VerifiedEvidence::passed)
                        .map(EvidenceVerifier.VerifiedEvidence::evidence)
                        .forEach(accepted::add);
            }
            log.info("证据校验：首次通过 {} 条 · 未通过 {} 条 → 定向修正后共采纳 {} 条",
                    firstPass.passed(), firstPass.failed(), accepted.size());
        }

        if (accepted.isEmpty() && !evidence.isEmpty()) {
            return new AskAnswer(null, List.of(), true,
                    "模型的证据全部未通过核验（文件、行号或片段与磁盘对不上），按设计不予返回",
                    AnsweredBy.LLM, retrievedFrom, hits.size(), selected.size(),
                    completion.promptTokens(), completion.completionTokens(), elapsedMillis(startNanos),
                    VerificationSummary.of(0, firstPass.failed(), repairs, "证据没过 ② 层，轮不到 ③ 层"));
        }
        if (evidence.isEmpty()) {
            // 验收标准：没有证据的答案不许返回 —— 所以这里不把模型的话原样递出去
            log.warn("模型给出了结论却没有可用证据，按拒答处理。结论原文：{}", parsed.answer());
            return new AskAnswer(null, List.of(), true,
                    "模型给出了结论但没有提供可用的 file+line 证据，按设计不予返回",
                    AnsweredBy.LLM, retrievedFrom, hits.size(), selected.size(),
                    completion.promptTokens(), completion.completionTokens(), elapsedMillis(startNanos),
                    VerificationSummary.none());
        }

        // ---- ③ 层：这段代码**支持**这条结论吗（①② 层证明不了这件事，见 SupportCheck 的注释）----
        SupportCheck support = runSupportCheck(question, parsed.answer(), accepted, repoRoot);
        if (support.flagged() && supportChecker.rejectOnUnsupported()) {
            log.warn("③ 层判定为不支持，按拒答处理（readcodeai.verify.support-check=reject）：{}", support.reason());
            return new AskAnswer(null, accepted, true,
                    "证据通过了磁盘核验，但未通过 ③ 层判定（证据不支持结论）：" + support.reason(),
                    AnsweredBy.LLM, retrievedFrom, hits.size(), selected.size(),
                    completion.promptTokens(), completion.completionTokens(), elapsedMillis(startNanos),
                    VerificationSummary.of(accepted.size(), firstPass.failed(), repairs, "判成不支持并拒答")
                            .withSupport(support));
        }

        return new AskAnswer(parsed.answer(), accepted, false, null, AnsweredBy.LLM,
                retrievedFrom, hits.size(), selected.size(),
                completion.promptTokens(), completion.completionTokens(), elapsedMillis(startNanos),
                new VerificationSummary(accepted.size(), firstPass.failed(), repairs, support));
    }

    /**
     * ③ 层判定的统一入口：**核验器自己崩了也不能让这次问答失败**。
     *
     * <p>崩了记成 {@link SupportCheck.Status#UNAVAILABLE}（= 这次没核验成），
     * 而不是"通过"也不是"拒绝" —— 前两层已经证明了证据真实存在，那个结果不该被一次判定故障抹掉。
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

    /**
     * 确定性路线的回答：**答案直接由查询结果生成，不过模型**。
     *
     * <p>所以它更快、更省、也**在没配 LLM 时照样可用** —— 这正是「可降级」设计想要的样子：
     * 剥掉模型之后，工具仍然是个能用的工具，而不是一个空壳。
     *
     * <p>证据里**第一条永远是目标符号自身**：即使「没有任何地方调用它」，
     * 也要能指出「你说的是这个符号」，否则一个空证据列表会让使用者无从判断。
     */
    private AskAnswer answerDeterministically(QueryRouter.Routed routed, Path repoRoot, long latencyMs) {
        List<AskEvidence> evidence = new ArrayList<>();
        SymbolView target = routed.targets().get(0);

        String answer;
        switch (routed.route()) {
            case LOCATE -> {
                // 定位题的答案**就是**符号的位置，所以目标本身是证据
                evidence.add(symbolEvidence(target, target.kind() + " " + target.signature()));
                if (routed.targets().size() > 1) {
                    routed.targets().stream().skip(1).forEach(symbol -> evidence.add(
                            symbolEvidence(symbol, symbol.kind() + " " + symbol.signature())));
                }
                answer = "共找到 " + routed.targets().size() + " 个同名符号，第一个是 " + target.location();
            }
            case CALLERS -> {
                List<CallSiteView> callers = symbolQueryService.callers(target.id());
                var resolvedCallers = callers.stream()
                        .filter(c -> c.symbolId() != null)
                        .toList();
                resolvedCallers.forEach(call -> evidence.add(new AskEvidence(
                        call.callSiteFile(), call.callLine(), call.callLine(), "",
                        "被 " + call.symbolQualifiedName() + " 调用")));
                answer = resolvedCallers.isEmpty()
                        ? target.qualifiedName() + " 在当前索引里没有任何调用点（可能是入口方法，也可能是死代码）"
                        : target.qualifiedName() + " 共有 " + resolvedCallers.size() + " 处调用";
            }
            case CALLEES -> {
                List<CallSiteView> callees = symbolQueryService.callees(target.id());
                var resolved = callees.stream().filter(CallSiteView::resolved).toList();
                long unresolved = callees.size() - resolved.size();
                for (CallSiteView call : resolved) {
                    // 证据指向**被调用者的定义位置**（而不是调用点）：既更有用（"你调用了它，它在哪"），
                    // 也让判卷口径与其他题型统一成「位置集合比较」
                    SymbolView callee = symbolQueryService.requireSymbol(call.symbolId());
                    evidence.add(symbolEvidence(callee, "被 " + target.name() + " 调用"));
                }
                answer = target.qualifiedName() + " 调用了 " + resolved.size() + " 个已解析的目标"
                        + (unresolved > 0
                        ? "，另有 " + unresolved + " 处未解析（外部依赖或静态分析盲区，未计入）" : "");
            }
            case STRUCTURE -> {
                List<SymbolView> members = symbolQueryService.children(target.id());
                members.forEach(member -> evidence.add(
                        symbolEvidence(member, member.kind() + " " + member.signature())));
                if (members.isEmpty()) {
                    answer = target.qualifiedName() + " 在当前索引里没有任何成员";
                } else {
                    String names = members.stream().limit(20).map(SymbolView::name)
                            .collect(Collectors.joining("、"));
                    answer = target.qualifiedName() + " 有 " + members.size() + " 个成员："
                            + names + (members.size() > 20 ? " 等" : "");
                }
            }
            case IMPLEMENTATIONS -> {
                List<SymbolView> implementations = symbolQueryService.implementations(target.id());
                implementations.forEach(impl -> evidence.add(
                        symbolEvidence(impl, "实现了 " + target.name() + " 的 " + impl.kind())));
                answer = implementations.isEmpty()
                        ? target.qualifiedName() + " 在当前索引里没有任何实现类或子类"
                        : target.qualifiedName() + " 有 " + implementations.size() + " 个实现类或子类";
            }
            default -> throw new IllegalStateException("不是确定性路线：" + routed.route());
        }

        // 集合类题目在结果为空时（例如"没有任何调用点"）也要能指出**你说的是哪个符号** ——
        // 否则一个空证据列表会让使用者无从判断。**但结果非空时不能把目标塞进去**：
        // 证据集合就是答案集合，多一个元素就不再等于答案（这个偏差是评估集实测抓出来的）。
        if (evidence.isEmpty()) {
            evidence.add(symbolEvidence(target, "题目指向的符号："
                    + target.kind() + " " + target.signature()));
        }

        // 静态路线的证据来自我们自己的索引，同样会漂移（源码改动后行号就失效了）——
        // 所以**一样要核验**：只不过它过的是 ① 层（文件与行号有效性），片段这一项为空。
        EvidenceVerifier.Report report = evidenceVerifier.verify(repoRoot, evidence);
        List<AskEvidence> accepted = report.evidence().stream()
                .filter(EvidenceVerifier.VerifiedEvidence::passed)
                .map(EvidenceVerifier.VerifiedEvidence::evidence)
                .toList();
        if (accepted.size() < evidence.size()) {
            log.warn("静态路线的证据有 {} 条未通过核验（索引可能已过期）", evidence.size() - accepted.size());
        }

        List<String> origins = accepted.stream().map(AskEvidence::location).toList();
        // 静态路线不做 ③ 层判定：答案本身就是查询结果，没有"模型组织语言"这一步，
        // 也就没有"结论与证据对不上"的可能 —— 那正是 ③ 层要防的东西
        return new AskAnswer(answer, accepted, false, null, AnsweredBy.STATIC,
                origins, 0, 0, 0, 0, latencyMs,
                VerificationSummary.of(accepted.size(), report.failed(), List.of(),
                        "静态路线：答案由查询算出，不经模型组织"));
    }

    /** 静态路线里「指向某个符号」的证据：行号来自索引，片段留空（内容核验交给 ① 层）。 */
    private static AskEvidence symbolEvidence(SymbolView symbol, String why) {
        return new AskEvidence(symbol.filePath(), symbol.startLine(), symbol.endLine(), "", why);
    }

    private String buildUserPrompt(String question, List<ChunkHit> chunks) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("问题：").append(question).append("\n\n可用的代码片段：\n");
        int index = 1;
        for (ChunkHit chunk : chunks) {
            prompt.append("\n[片段 ").append(index++).append("] file=").append(chunk.filePath())
                    .append(" lines=").append(chunk.startLine()).append('-').append(chunk.endLine());
            if (chunk.symbolQualifiedName() != null) {
                prompt.append("  symbol=").append(chunk.symbolQualifiedName());
            }
            prompt.append('\n').append(chunk.content()).append('\n');
        }
        return prompt.toString();
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String abbreviate(String raw) {
        if (raw == null) {
            return "(空)";
        }
        return raw.length() <= 200 ? raw : raw.substring(0, 200) + "...";
    }
}
