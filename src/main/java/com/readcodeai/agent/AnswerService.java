package com.readcodeai.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.readcodeai.agent.model.AnsweredBy;
import com.readcodeai.agent.model.AskAnswer;
import com.readcodeai.agent.model.AskEvidence;
import com.readcodeai.config.LlmClient;
import com.readcodeai.config.ReadCodeAiProperties;
import com.readcodeai.retrieve.QueryRouter;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.TextRetriever;
import com.readcodeai.retrieve.model.CallSiteView;
import com.readcodeai.retrieve.model.ChunkHit;
import com.readcodeai.retrieve.model.SymbolView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

// 注意：Spring Boot 4 用的是 Jackson 3 —— databind 搬到了 tools.jackson，
// 但注解仍在 com.fasterxml.jackson.annotation（这就是为什么客户端那边的注解能编译、databind 却不能）
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

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

    private static final String SYSTEM_PROMPT = """
            你是代码库理解助手。只能依据下面提供的代码片段回答问题。

            硬性要求：
            1. 每条结论都必须给出证据：文件路径 + 起止行号，且必须与片段标注里的完全一致，不许自己编造。
            2. 如果提供的片段不足以回答，就把 refused 设为 true 并在 refusalReason 里说明缺什么。不要猜测。
            3. 只输出 JSON，不要 Markdown 代码块，不要任何解释性文字。
            4. answer 用中文、简洁，不要复述代码。

            输出格式：
            {"answer":"结论","evidence":[{"file":"片段里的文件路径","startLine":1,"endLine":2,"why":"这段代码说明了什么"}],"refused":false,"refusalReason":""}
            """;

    private final TextRetriever textRetriever;
    private final SymbolQueryService symbolQueryService;
    private final QueryRouter queryRouter;
    private final LlmClient llmClient;
    private final ReadCodeAiProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AnswerService(TextRetriever textRetriever,
                         SymbolQueryService symbolQueryService,
                         QueryRouter queryRouter,
                         LlmClient llmClient,
                         ReadCodeAiProperties properties) {
        this.textRetriever = textRetriever;
        this.symbolQueryService = symbolQueryService;
        this.queryRouter = queryRouter;
        this.llmClient = llmClient;
        this.properties = properties;
    }

    /**
     * @param scopePath 可选：把检索范围限制在某个文件/目录（路径包含匹配）。第 2 步先支持单文件范围，
     *                  缩小问题空间便于定位问题。
     */
    public AskAnswer ask(Long repoId, String question, String scopePath, Integer topK) {
        long start = System.nanoTime();
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }

        long effectiveRepoId = repoId != null ? repoId : symbolQueryService.requireLatestRepoId();

        // 先路由：**确定性问题根本不叫模型**。「谁调用了 X」的答案是调用图算出来的，
        // 让模型去"组织"它，等于把确定性换成不确定性。
        QueryRouter.Routed routed = queryRouter.route(effectiveRepoId, question,
                name -> symbolQueryService.locate(effectiveRepoId, name, 10));
        if (routed.isDeterministic()) {
            log.info("问题走确定性路线：route={} targets={}", routed.route(), routed.targets().size());
            return answerDeterministically(routed, elapsedMillis(start));
        }

        // 走到这里才需要模型；没配就按降级处理（静态分析那几层照常可用）
        if (!llmClient.available()) {
            throw new LlmUnavailableException("未配置 LLM（readcodeai.llm.*），语义问答不可用；"
                    + "定位 / 调用关系 / 实现类 / 全文检索等确定性能力不受影响");
        }

        int limit = topK != null && topK > 0 ? topK : properties.getRetrieve().getTopK();
        List<ChunkHit> hits = textRetriever.search(effectiveRepoId, question, limit);
        if (scopePath != null && !scopePath.isBlank()) {
            String scope = scopePath.replace('\\', '/');
            hits = hits.stream().filter(hit -> hit.filePath().contains(scope)).toList();
        }
        List<ChunkHit> selected = withinBudget(hits);
        List<String> retrievedFrom = selected.stream().map(ChunkHit::location).toList();
        if (selected.isEmpty()) {
            return AskAnswer.refused("在索引里没有检索到与该问题相关的代码片段",
                    retrievedFrom, 0, elapsedMillis(start));
        }

        LlmClient.Completion completion = llmClient.complete(SYSTEM_PROMPT, buildUserPrompt(question, selected));
        LlmAnswer parsed = parse(completion.content());

        if (Boolean.TRUE.equals(parsed.refused())) {
            return new AskAnswer(null, List.of(), true,
                    parsed.refusalReason() == null || parsed.refusalReason().isBlank()
                            ? "模型判断给定片段不足以回答" : parsed.refusalReason(),
                    AnsweredBy.LLM, retrievedFrom, selected.size(),
                    completion.promptTokens(), completion.completionTokens(), elapsedMillis(start));
        }

        // 解析宽容、校验严格：小模型常给 null 或缺字段，容错是为了不整条失败；
        // 但缺字段的证据必须被丢掉 —— 放出去就等于拿脏数据当证据。
        List<AskEvidence> evidence = validEvidence(parsed.evidence());
        if (evidence.size() < (parsed.evidence() == null ? 0 : parsed.evidence().size())) {
            log.warn("模型给出的证据里有 {} 条缺文件或行号，已丢弃",
                    parsed.evidence().size() - evidence.size());
        }
        if (evidence.isEmpty()) {
            // 验收标准：没有证据的答案不许返回 —— 所以这里不把模型的话原样递出去
            log.warn("模型给出了结论却没有可用证据，按拒答处理。结论原文：{}", parsed.answer());
            return new AskAnswer(null, List.of(), true,
                    "模型给出了结论但没有提供可用的 file+line 证据，按设计不予返回",
                    AnsweredBy.LLM, retrievedFrom, selected.size(),
                    completion.promptTokens(), completion.completionTokens(), elapsedMillis(start));
        }

        return new AskAnswer(parsed.answer(), evidence, false, null, AnsweredBy.LLM,
                retrievedFrom, selected.size(),
                completion.promptTokens(), completion.completionTokens(), elapsedMillis(start));
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
    private AskAnswer answerDeterministically(QueryRouter.Routed routed, long latencyMs) {
        List<AskEvidence> evidence = new ArrayList<>();
        SymbolView target = routed.targets().get(0);
        evidence.add(new AskEvidence(target.filePath(), target.startLine(), target.endLine(),
                target.kind() + " " + target.signature()));

        String answer;
        switch (routed.route()) {
            case LOCATE -> {
                if (routed.targets().size() > 1) {
                    routed.targets().stream().skip(1).forEach(symbol -> evidence.add(
                            new AskEvidence(symbol.filePath(), symbol.startLine(), symbol.endLine(),
                                    symbol.kind() + " " + symbol.signature())));
                }
                answer = "共找到 " + routed.targets().size() + " 个同名符号，第一个是 " + target.location();
            }
            case CALLERS -> {
                List<CallSiteView> callers = symbolQueryService.callers(target.id());
                var resolvedCallers = callers.stream()
                        .filter(c -> c.symbolId() != null)
                        .toList();
                resolvedCallers.forEach(call -> evidence.add(new AskEvidence(
                        call.callSiteFile(), call.callLine(), call.callLine(),
                        "被 " + call.symbolQualifiedName() + " 调用")));
                answer = resolvedCallers.isEmpty()
                        ? target.qualifiedName() + " 在当前索引里没有任何调用点（可能是入口方法，也可能是死代码）"
                        : target.qualifiedName() + " 共有 " + resolvedCallers.size() + " 处调用";
            }
            case CALLEES -> {
                List<CallSiteView> callees = symbolQueryService.callees(target.id());
                var resolved = callees.stream().filter(CallSiteView::resolved).toList();
                long unresolved = callees.size() - resolved.size();
                resolved.forEach(call -> evidence.add(new AskEvidence(
                        call.callSiteFile(), call.callLine(), call.callLine(),
                        "调用了 " + call.calleeRaw())));
                answer = target.qualifiedName() + " 调用了 " + resolved.size() + " 个已解析的目标"
                        + (unresolved > 0
                        ? "，另有 " + unresolved + " 处未解析（外部依赖或静态分析盲区，未计入）" : "");
            }
            case IMPLEMENTATIONS -> {
                List<SymbolView> implementations = symbolQueryService.implementations(target.id());
                implementations.forEach(impl -> evidence.add(new AskEvidence(
                        impl.filePath(), impl.startLine(), impl.endLine(),
                        "实现了 " + target.name() + " 的 " + impl.kind())));
                answer = implementations.isEmpty()
                        ? target.qualifiedName() + " 在当前索引里没有任何实现类或子类"
                        : target.qualifiedName() + " 有 " + implementations.size() + " 个实现类或子类";
            }
            default -> throw new IllegalStateException("不是确定性路线：" + routed.route());
        }

        List<String> origins = evidence.stream().map(AskEvidence::location).toList();
        return new AskAnswer(answer, evidence, false, null, AnsweredBy.STATIC,
                origins, 0, 0, 0, latencyMs);
    }

    /** 丢掉缺文件或缺行号的证据条目 —— 这类条目无法核验，留着只会污染答案。 */
    private static List<AskEvidence> validEvidence(List<RawEvidence> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream()
                .filter(e -> e.file() != null && !e.file().isBlank())
                .filter(e -> e.startLine() != null && e.endLine() != null)
                .filter(e -> e.startLine() > 0 && e.endLine() >= e.startLine())
                .map(e -> new AskEvidence(e.file(), e.startLine(), e.endLine(),
                        e.why() == null ? "" : e.why()))
                .toList();
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

    /** 按 token 预算取片段：相关性已由检索排序，这里只做截断，不重排。 */
    private List<ChunkHit> withinBudget(List<ChunkHit> hits) {
        long budget = properties.getRetrieve().getMaxContextTokens();
        List<ChunkHit> selected = new ArrayList<>();
        long used = 0;
        for (ChunkHit hit : hits) {
            if (!selected.isEmpty() && used + hit.tokenEstimate() > budget) {
                break;
            }
            selected.add(hit);
            used += hit.tokenEstimate();
        }
        return selected;
    }

    /**
     * 解析模型的 JSON。
     *
     * <p>容错但从宽到严：小模型经常把 JSON 包在 Markdown 代码块里，或前后加一句话 ——
     * 所以先剥壳再取最外层大括号。**但解析不出来就是失败**，不会退回"把原文当答案"，
     * 那样等于绕过了「必须带证据」这条验收标准。
     */
    private LlmAnswer parse(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                text = text.substring(firstNewline + 1, lastFence).strip();
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("模型没有返回 JSON，无法提取带证据的答案。原文："
                    + abbreviate(raw));
        }
        try {
            return objectMapper.readValue(text.substring(start, end + 1), LlmAnswer.class);
        } catch (Exception e) {
            throw new IllegalStateException("模型返回的 JSON 无法解析：" + e.getMessage()
                    + "。原文：" + abbreviate(raw), e);
        }
    }

    private static String abbreviate(String raw) {
        if (raw == null) {
            return "(空)";
        }
        return raw.length() <= 300 ? raw : raw.substring(0, 300) + "...";
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * 模型返回的 JSON 结构（内部传输用，不对外暴露）。
     *
     * <p><b>全部字段用包装类型</b>，不用原始类型：实测小模型会把 {@code refused} 给成 {@code null}，
     * 而 Jackson 3 默认拒绝 null → 原始类型，一条好答案会因为一个缺字段整条解析失败。
     * 宽容解析 + 严格校验，比"格式必须完美"更符合真实模型的行为。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record LlmAnswer(String answer, List<RawEvidence> evidence, Boolean refused, String refusalReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawEvidence(String file, Integer startLine, Integer endLine, String why) {
    }
}
