package com.readcodeai.agent.log;

import com.readcodeai.agent.model.AgentAnswer;
import com.readcodeai.agent.model.AskAnswer;
import com.readcodeai.agent.model.AskEvidence;
import com.readcodeai.agent.model.SupportCheck;
import com.readcodeai.agent.model.VerificationSummary;
import com.readcodeai.config.ReadCodeAiProperties;
import com.readcodeai.retrieve.QueryRouter;
import com.readcodeai.retrieve.model.SymbolView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import tools.jackson.databind.ObjectMapper;

/**
 * 问答流水：**把"这次问答花了什么"记成一行**，并给出累计统计。
 *
 * <h3>为什么它是独立一层，而不是在 AnswerService 里顺手写一行 SQL</h3>
 * 一次问答的出口有四个（确定性路线 / 单跳语义 / 多跳 / 取缓存），每个出口的数据形状还不一样
 * （{@code AskAnswer} 与 {@code AgentAnswer}）。把"翻译成流水"这件事收在一处，
 * 四个出口才不会有三种口径 —— 而口径不一致的统计比没有统计更糟。
 *
 * <h3>两条硬约束</h3>
 * <ol>
 *   <li><b>记账失败绝不影响回答</b>：所有异常在这里吞掉（首次记日志，之后静默 ——
 *       否则一次数据库故障会刷满日志，把真正的错误淹掉）。记账是"账本"，不是正确性依赖。</li>
 *   <li><b>token 是两笔账</b>：生成答案的用量与 ③ 层核验的用量分开存
 *       （{@code support_*} 两列），金额把两笔按同一单价加起来。
 *       多跳路径里 ③ 层核验在预算循环**之外**调用、没有计入 {@code BudgetGuard}，
 *       所以这里的相加不会重复计（改动 AgentLoop 时要注意这一点）。</li>
 * </ol>
 */
@Service
public class AnswerLogService {

    private static final Logger log = LoggerFactory.getLogger(AnswerLogService.class);

    /** 与表里的列宽一致：超长就截断 —— 宁可少存几个字，不能让一条流水把整次问答带崩。 */
    private static final int MAX_QUESTION = 512;
    private static final int MAX_ROUTE = 1024;
    private static final int MAX_REASON = 512;

    /** 只用于 {@link #routeJson}（流水里的一栏，不参与问答逻辑）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AnswerLogRepository repository;
    private final ReadCodeAiProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    /** 同一类失败只记一次日志（与 RedisAnswerCache 同一条纪律：故障不许刷屏）。 */
    private final AtomicBoolean failureLogged = new AtomicBoolean(false);

    public AnswerLogService(AnswerLogRepository repository, ReadCodeAiProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * 记一次单跳 / 静态问答。{@code mode} 是**实际走的路线**（STATIC / SINGLE_HOP），
     * 由调用方给出 —— 它比 {@code answeredBy} 更能说明"这次为什么没叫模型"。
     */
    public void recordAsk(Long repoId, Long questionId, AnswerLogSource source, String question,
                          String mode, String routeJson, AskAnswer answer) {
        if (answer == null) {
            return;
        }
        try {
            VerificationSummary verification = orNone(answer.verification());
            SupportCheck support = supportOf(verification);
            repository.insert(new AnswerLogEntry(
                    repoId, questionId, source.name(), clamp(question, MAX_QUESTION), mode,
                    answer.answeredBy().name(), clamp(routeJson, MAX_ROUTE), 0,
                    answer.promptTokens(), answer.completionTokens(),
                    support.promptTokens(), support.completionTokens(),
                    estimateCost(answer.promptTokens(), answer.completionTokens())
                            + estimateCost(support.promptTokens(), support.completionTokens()),
                    answer.latencyMs(), verification.verified(), verification.mismatch(),
                    support.status().name(), answer.refused(), clamp(answer.refusalReason(), MAX_REASON),
                    false,
                    json(new Snapshot(answer.answer(), answer.evidence(), answer.retrievedFrom(),
                            answer.refusalReason(), null, List.of(), verification))));
        } catch (RuntimeException e) {
            warnOnce(e);
        }
    }

    /**
     * 记一次 Agent 问答（多跳，或"取缓存"）。
     *
     * <p>{@code cacheHit=true} 时 token / 金额**保持原值**：它们是"这份答案当初花了多少"，
     * 而这一列本身说明"这次没有再花"。统计时两笔账分开算（见 {@link AnswerLogStats}）。
     */
    public void recordAgent(Long repoId, AnswerLogSource source, String question, String mode,
                            String routeJson, boolean cacheHit, AgentAnswer answer) {
        if (answer == null) {
            return;
        }
        try {
            VerificationSummary verification = orNone(answer.verification());
            SupportCheck support = supportOf(verification);
            repository.insert(new AnswerLogEntry(
                    repoId, null, source.name(), clamp(question, MAX_QUESTION), mode,
                    answer.answeredBy().name(), clamp(routeJson, MAX_ROUTE), answer.rounds(),
                    answer.promptTokens(), answer.completionTokens(),
                    support.promptTokens(), support.completionTokens(),
                    answer.estimatedCost() + estimateCost(support.promptTokens(), support.completionTokens()),
                    answer.latencyMs(), verification.verified(), verification.mismatch(),
                    support.status().name(), answer.refused(), clamp(answer.reason(), MAX_REASON),
                    cacheHit,
                    json(new Snapshot(answer.answer(), answer.evidence(), List.of(),
                            answer.reason(), answer.stopReason() == null ? null : answer.stopReason().name(),
                            answer.trailDigest(), verification))));
        } catch (RuntimeException e) {
            warnOnce(e);
        }
    }

    /** 累计统计；{@code repoId} 为 null 时统计全部仓库。 */
    public AnswerLogStats stats(Long repoId) {
        return repository.stats(repoId);
    }

    /**
     * 路由结果写成 JSON 存进流水 —— 「这个问题被路由成了什么、命中了哪个符号」
     * 是事后诊断答错原因的第一手材料（答错了先看路由对不对，再看检索和模型）。
     *
     * <p>放这里而不是各调用方：单跳与多跳两条路都记这一栏，形状必须一样。
     */
    public static String routeJson(QueryRouter.Routed routed) {
        return routeJson(routed, false);
    }

    /**
     * @param deep 这次是不是**深链模式** —— 只记 true（默认 false 不写进 JSON），
     *             这样"深链到底有没有用"将来能用流水数据回答，而不是靠印象。
     */
    public static String routeJson(QueryRouter.Routed routed, boolean deep) {
        if (routed == null) {
            return deep ? "{\"deep\":true}" : null;
        }
        try {
            Map<String, Object> route = new LinkedHashMap<>();
            route.put("route", routed.route().name());
            route.put("targets", routed.targets().stream().map(SymbolView::qualifiedName).toList());
            if (deep) {
                route.put("deep", true);
            }
            return MAPPER.writeValueAsString(route);
        } catch (RuntimeException e) {
            // 序列化不了也要留下最要紧的那一栏
            return "{\"route\":\"" + routed.route().name() + "\"}";
        }
    }

    /** {@code answer_json} 的内容 —— 事后回溯"这次为什么这么答"要的东西（轨迹只存摘要，不存全过程）。 */
    private record Snapshot(String answer, List<AskEvidence> evidence, List<String> retrievedFrom,
                            String refusalReason, String stopReason, List<String> trail,
                            VerificationSummary verification) {
    }

    private double estimateCost(int promptTokens, int completionTokens) {
        ReadCodeAiProperties.Llm llm = properties.getLlm();
        return promptTokens / 1_000_000.0 * llm.getInputPricePerMillion()
                + completionTokens / 1_000_000.0 * llm.getOutputPricePerMillion();
    }

    private static VerificationSummary orNone(VerificationSummary verification) {
        return verification == null ? VerificationSummary.none() : verification;
    }

    private static SupportCheck supportOf(VerificationSummary verification) {
        return verification.support() == null
                ? SupportCheck.notChecked("本次没有记录 ③ 层判定") : verification.support();
    }

    private String json(Snapshot snapshot) {
        try {
            return mapper.writeValueAsString(snapshot);
        } catch (RuntimeException e) {
            // 序列化不了就只丢回溯细节，**流水本身照记**（成本与耗时才是统计要用的）
            log.debug("流水里的答案快照序列化失败（不影响统计）", e);
            return null;
        }
    }

    private static String clamp(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max - 1) + "…";
    }

    private void warnOnce(RuntimeException e) {
        if (failureLogged.compareAndSet(false, true)) {
            log.warn("写问答流水失败（不影响本次回答，后续同类失败不再重复记录）：{}", e.toString());
        } else {
            log.debug("写问答流水失败：{}", e.toString());
        }
    }
}
