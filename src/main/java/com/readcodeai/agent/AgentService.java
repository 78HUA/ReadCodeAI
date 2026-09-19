package com.readcodeai.agent;

import com.readcodeai.agent.model.AgentAnswer;
import com.readcodeai.agent.model.AgentMode;
import com.readcodeai.agent.model.AnsweredBy;
import com.readcodeai.agent.model.AskAnswer;
import com.readcodeai.agent.model.StopReason;
import com.readcodeai.config.BudgetGuard;
import com.readcodeai.config.LlmClient;
import com.readcodeai.config.ReadCodeAiProperties;
import com.readcodeai.retrieve.QueryRouter;
import com.readcodeai.retrieve.SymbolLookups;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.SymbolView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 问答入口：决定这次问答**走哪条路**，并把两条路的结果统一成同一种结果类型。
 *
 * <h3>三条路，按"能算准的别猜"排序</h3>
 * <ol>
 *   <li><b>确定性问题</b>（谁调用了它 / 它调用了谁 / 有哪些实现 / 有哪些成员）→ 查表算出来，
 *       **不经模型**。这类问题问模型等于把确定性换成不确定性。</li>
 *   <li><b>单跳语义问题</b>（"登录检查在哪做的"）→ 一次检索 + 模型组织语言。</li>
 *   <li><b>链式问题</b>（"这个参数从哪来"、"改这个会影响哪些地方"）→ {@link AgentLoop 多跳}：
 *       模型自己决定往哪跳、跳几跳、什么时候停。</li>
 * </ol>
 *
 * <p><b>为什么链式问题不能交给单跳</b>：单跳只有一次检索机会，
 * 而"间接调用者"这种答案要沿调用图跳几跳才拿得到 —— 单跳对它不是答得差，是根本答不了。
 * 所以链式问题**即使被路由判成确定性问题**（例如"哪些方法最终会调用 X"命中 CALLERS 模式），
 * 在多跳模式下也会交给 Agent。
 *
 * <p>已知代价：确定性问题与单跳路径都转发给 {@link AnswerService}，它内部会再路由一次
 * （多两条查询，实测 1–3 ms）。换来的是两条路径的行为完全一致、不需要两处维护 —— 值得。
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final AnswerService answerService;
    private final AgentLoop agentLoop;
    private final QueryRouter queryRouter;
    private final SymbolQueryService symbolQueryService;
    private final LlmClient llmClient;
    private final ReadCodeAiProperties properties;

    public AgentService(AnswerService answerService, AgentLoop agentLoop, QueryRouter queryRouter,
                        SymbolQueryService symbolQueryService, LlmClient llmClient,
                        ReadCodeAiProperties properties) {
        this.answerService = answerService;
        this.agentLoop = agentLoop;
        this.queryRouter = queryRouter;
        this.symbolQueryService = symbolQueryService;
        this.llmClient = llmClient;
        this.properties = properties;
    }

    public AgentAnswer ask(Long repoId, String question, AgentMode mode, String scopePath, Integer topK) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        AgentMode effectiveMode = mode == null ? AgentMode.MULTI_HOP : mode;
        long effectiveRepoId = repoId != null ? repoId : symbolQueryService.requireLatestRepoId();
        Path repoRoot = Path.of(symbolQueryService.requireRepo(effectiveRepoId).rootPath());

        QueryRouter.Routed routed = queryRouter.route(effectiveRepoId, question,
                SymbolLookups.of(symbolQueryService, effectiveRepoId));

        boolean wantsMultiHop = effectiveMode == AgentMode.MULTI_HOP
                && (!routed.isDeterministic() || ChainIntent.isChainQuestion(question));

        if (!wantsMultiHop) {
            AskAnswer answer = answerService.ask(effectiveRepoId, question, scopePath, topK);
            StopReason reason = answer.answeredBy() == AnsweredBy.STATIC
                    ? StopReason.STATIC : StopReason.SINGLE_HOP;
            log.info("问答走单跳/静态路线：answeredBy={}", answer.answeredBy());
            return AgentAnswer.from(answer, effectiveMode, reason);
        }

        if (!llmClient.available()) {
            throw new LlmUnavailableException("未配置 LLM（readcodeai.llm.*），多跳检索不可用；"
                    + "定位 / 调用关系 / 实现类 / 全文检索等确定性能力不受影响");
        }
        // scopePath / topK 是多跳里用不上的旋钮：检索范围由模型自己决定，手动限定反而会把它框死
        return agentLoop.run(effectiveRepoId, repoRoot, question, seedObservations(routed),
                BudgetGuard.of(properties));
        // 注：种子把目标符号的定义行一并交给模型（seedObservations 里同时返回位置），
        // 因此"引用目标自身的定义"是有据可依的，不会被"引用必须落在轨迹里"这条规则误杀。
    }

    /**
     * 第 0 跳：把**确定性路由已经算准的东西**交给模型当起点。
     *
     * <p>这不是提示词工程，而是分工：符号解析是确定性的活（能算准），
     * 让模型从算准的位置起步，既省一轮预算，也避免它去猜"问题里说的是哪个同名方法"。
     */
    private static AgentLoop.Seeds seedObservations(QueryRouter.Routed routed) {
        if (routed.targets().isEmpty()) {
            return AgentLoop.Seeds.none();
        }
        List<String> seeds = new ArrayList<>();
        StringBuilder seed = new StringBuilder("[第 0 跳 · 系统] 确定性路由已把问题里的符号解析出来，可以直接对它用工具：\n");
        for (SymbolView target : routed.targets()) {
            seed.append("- ").append(target.kind()).append(' ').append(target.qualifiedName())
                    .append("  (").append(target.location()).append(")\n");
        }
        seeds.add(seed.toString());
        List<com.readcodeai.agent.model.AskEvidence> locations = routed.targets().stream()
                .map(target -> new com.readcodeai.agent.model.AskEvidence(target.filePath(),
                        target.startLine(), target.endLine(), "", "确定性路由解析出的符号"))
                .toList();
        return AgentLoop.Seeds.of(seeds, locations);
    }
}
