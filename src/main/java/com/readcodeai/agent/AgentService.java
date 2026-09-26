package com.readcodeai.agent;

import com.readcodeai.agent.cache.AnswerCache;
import com.readcodeai.agent.log.AnswerLogService;
import com.readcodeai.agent.log.AnswerLogSource;
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
import com.readcodeai.retrieve.model.RepoView;
import com.readcodeai.retrieve.model.SymbolView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
    /** Spring AI 版多跳引擎：只有 `readcodeai.agent.engine=spring-ai` 时才会被装配，所以用 Provider 懒取。 */
    private final ObjectProvider<com.readcodeai.agent.springai.SpringAiAgentLoop> springAiEngine;
    /** 非 null 时强制使用该引擎（给直接 new 的测试：它们注入的是手写客户端，必须配手写引擎）。 */
    private final ReadCodeAiProperties.Agent.Engine engineOverride;
    private final QueryRouter queryRouter;
    private final SymbolQueryService symbolQueryService;
    private final LlmClient llmClient;
    private final AnswerCache answerCache;
    private final ReadCodeAiProperties properties;
    private final AnswerLogService answerLogService;
    private final SummaryAnswerer summaryAnswerer;

    /**
     * 兼容构造器：**给直接 new 的测试用**，固定走手写引擎 —— 它们注入的是
     * {@code ScriptedLlmClient}（实现 {@code LlmClient}），配 Spring AI 引擎是配不上的。
     *
     * <p>这样这次引入 Spring AI 的改动对既有测试是"零改动"的。
     */
    public AgentService(AnswerService answerService, AgentLoop agentLoop, QueryRouter queryRouter,
                        SymbolQueryService symbolQueryService, LlmClient llmClient,
                        AnswerCache answerCache, ReadCodeAiProperties properties,
                        AnswerLogService answerLogService, SummaryAnswerer summaryAnswerer) {
        this(answerService, agentLoop, queryRouter, symbolQueryService, llmClient, answerCache, properties,
                answerLogService, summaryAnswerer, null, ReadCodeAiProperties.Agent.Engine.HANDWRITTEN);
    }

    /** Spring 用的构造器：引擎由配置决定（{@code readcodeai.agent.engine}）。 */
    @org.springframework.beans.factory.annotation.Autowired
    public AgentService(AnswerService answerService, AgentLoop agentLoop, QueryRouter queryRouter,
                        SymbolQueryService symbolQueryService, LlmClient llmClient,
                        AnswerCache answerCache, ReadCodeAiProperties properties,
                        AnswerLogService answerLogService, SummaryAnswerer summaryAnswerer,
                        ObjectProvider<com.readcodeai.agent.springai.SpringAiAgentLoop> springAiEngine) {
        this(answerService, agentLoop, queryRouter, symbolQueryService, llmClient, answerCache, properties,
                answerLogService, summaryAnswerer, springAiEngine, null);
    }

    private AgentService(AnswerService answerService, AgentLoop agentLoop, QueryRouter queryRouter,
                         SymbolQueryService symbolQueryService, LlmClient llmClient,
                         AnswerCache answerCache, ReadCodeAiProperties properties,
                         AnswerLogService answerLogService, SummaryAnswerer summaryAnswerer,
                         ObjectProvider<com.readcodeai.agent.springai.SpringAiAgentLoop> springAiEngine,
                         ReadCodeAiProperties.Agent.Engine engineOverride) {
        this.answerService = answerService;
        this.agentLoop = agentLoop;
        this.queryRouter = queryRouter;
        this.symbolQueryService = symbolQueryService;
        this.llmClient = llmClient;
        this.answerCache = answerCache;
        this.properties = properties;
        this.answerLogService = answerLogService;
        this.summaryAnswerer = summaryAnswerer;
        this.springAiEngine = springAiEngine;
        this.engineOverride = engineOverride;
    }

    /**
     * 生效的引擎：测试可以强制（{@link #engineOverride}），否则按配置
     * （{@code readcodeai.agent.engine}）。
     */
    private ReadCodeAiProperties.Agent.Engine effectiveEngine() {
        return engineOverride != null ? engineOverride : properties.getAgent().getEngine();
    }

    /**
     * 选哪个多跳引擎。
     *
     * <p>两个引擎实现同一个 {@link AgentEngine}，返回同一种 {@link AgentAnswer} ——
     * 所以下游（核验 / 缓存 / 记账 / 界面）一行都不用改。
     */
    private AgentEngine engine() {
        if (effectiveEngine() != ReadCodeAiProperties.Agent.Engine.SPRING_AI) {
            return agentLoop;
        }
        return springAiEngine.getObject();
    }

    /**
     * 实际生效的引擎名 —— **它要进答案缓存的键**：
     * 两个引擎可以共存，不带这一维就会"用 A 引擎问过、换 B 引擎拿到 A 的答案"。
     */
    private String resolvedEngine() {
        return effectiveEngine().value();
    }

    public AgentAnswer ask(Long repoId, String question, AgentMode mode, String scopePath, Integer topK) {
        return ask(repoId, question, mode, scopePath, topK, false);
    }

    /**
     * @param deep 深链模式（追问页勾选）：把多跳的轮次/时长/token 额度换大（见 {@link BudgetGuard#deepOf}），
     *             用来追长链。它只影响多跳那条路 —— 静态与单跳本来就是几毫秒到几十秒。
     *             <p><b>额度由配置给，不由前端传数字</b>：预算是闸门，前端只能选"要不要深链"。
     */
    public AgentAnswer ask(Long repoId, String question, AgentMode mode, String scopePath, Integer topK,
                           boolean deep) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        AgentMode effectiveMode = mode == null ? AgentMode.MULTI_HOP : mode;
        long effectiveRepoId = repoId != null ? repoId : symbolQueryService.requireLatestRepoId();
        RepoView repo = symbolQueryService.requireRepo(effectiveRepoId);
        Path repoRoot = Path.of(repo.rootPath());

        QueryRouter.Routed routed = queryRouter.route(effectiveRepoId, question,
                SymbolLookups.of(symbolQueryService, effectiveRepoId));

        // 总结类问题**不走检索**：答案不是藏在某几段代码里，而是"结构算出来 + 语义模型补"。
        // 走多跳的代价实测过：93 秒、6 轮、16.5k token，最后模型 JSON 还写坏了、按拒答返回。
        if (SummaryIntent.isSummaryQuestion(question)) {
            log.info("问题命中总结意图 → 结构化摘要（用户选的模式 {} 对它没有影响）", effectiveMode);
            AgentAnswer answer = summaryAnswerer.answer(effectiveRepoId, repoRoot, question,
                    AgentMode.SUMMARY);
            answerLogService.recordAgent(effectiveRepoId, AnswerLogSource.USER, question, "SUMMARY",
                    AnswerLogService.routeJson(routed, deep), false, answer);
            return answer;
        }

        boolean wantsMultiHop = effectiveMode == AgentMode.MULTI_HOP
                && (!routed.isDeterministic() || ChainIntent.isChainQuestion(question));

        // 只有"要叫模型"的那两条路才查缓存：确定性问题本来就只有几毫秒，缓存它没有收益，
        // 却凭空多一份可能过期的数据（见 AnswerCache 的注释）
        if (wantsMultiHop || !routed.isDeterministic()) {
            String indexedAt = repo.indexedAt() == null ? null : repo.indexedAt().toString();
            AgentAnswer cached = readCache(effectiveRepoId, indexedAt, question, effectiveMode);
            if (cached != null) {
                log.info("答案缓存命中：省下一次生成（当初花了 {} token）", cached.generationTokens());
                // 缓存命中同样要记账：**这次确实被问了**，只是没有重新生成 ——
                // cache_hit 列说明"token 记的是这份答案当初花的、这次省下了"
                answerLogService.recordAgent(effectiveRepoId, AnswerLogSource.USER, question,
                        cached.mode().name(), AnswerLogService.routeJson(routed), true, cached);
                return cached;
            }
        }

        if (!wantsMultiHop) {
            AskAnswer answer = answerService.ask(effectiveRepoId, question, scopePath, topK);
            StopReason reason = answer.answeredBy() == AnsweredBy.STATIC
                    ? StopReason.STATIC : StopReason.SINGLE_HOP;
            log.info("问答走单跳/静态路线：answeredBy={}", answer.answeredBy());
            AgentAnswer converted = AgentAnswer.from(answer, effectiveMode, reason);
            // 单跳也是"叫了模型"的路线，同样缓存（确定性问题不会走到这里）
            if (converted.answeredBy() == AnsweredBy.LLM) {
                putIfCacheable(effectiveRepoId, repo, question, effectiveMode, converted);
            }
            return converted;
        }

        AgentEngine engine = engine();
        if (!engine.available()) {
            throw new LlmUnavailableException(engine.unavailableReason());
        }
        // scopePath / topK 是多跳里用不上的旋钮：检索范围由模型自己决定，手动限定反而会把它框死
        AgentAnswer answer = engine.run(effectiveRepoId, repoRoot, question, seedObservations(routed),
                deep ? BudgetGuard.deepOf(properties) : BudgetGuard.of(properties));
        putIfCacheable(effectiveRepoId, repo, question, effectiveMode, answer);
        answerLogService.recordAgent(effectiveRepoId, AnswerLogSource.USER, question, "MULTI_HOP",
                AnswerLogService.routeJson(routed, deep), false, answer);
        return answer;
        // 注：转发单跳的那条路**不在这里记账** —— AnswerService 的出口已经记了一次，
        // 这里再记一次会让同一个问题在流水里出现两行（记账点必须与"一次问答"一一对应）
        // 注：种子把目标符号的定义行一并交给模型（seedObservations 里同时返回位置），
        // 因此"引用目标自身的定义"是有据可依的，不会被"引用必须落在轨迹里"这条规则误杀。
    }

    /**
     * 只缓存**模型给出、且没被拒答**的答案。
     *
     * <p>为什么拒答不进缓存：拒答的原因常常是临时的（模型接口抽风、输出格式坏、预算不够），
     * 缓存它等于把一次偶发失败固化下来。宁可下次重算。
     */
    private void putIfCacheable(long repoId, RepoView repo, String question, AgentMode mode,
                                AgentAnswer answer) {
        if (answer.refused() || answer.answeredBy() != AnsweredBy.LLM) {
            return;
        }
        String indexedAt = repo.indexedAt() == null ? null : repo.indexedAt().toString();
        try {
            answerCache.put(repoId, indexedAt, question, mode.name(), llmClient.model(), resolvedEngine(), answer);
        } catch (RuntimeException e) {
            // 缓存写失败只该表现为"下次还得重算"，绝不能影响这次回答
            log.warn("写答案缓存失败（不影响本次回答）：{}", e.toString());
        }
    }

    /**
     * 读缓存，**任何异常都按"没有缓存"处理**。
     *
     * <p>为什么不指望缓存实现自己吞异常：那是"每个实现都要记得做对"的约定，
     * 而这里是"无论实现怎么写，问答都照常"的保证 —— 缓存是加速手段，不是正确性依赖。
     */
    private AgentAnswer readCache(long repoId, String indexedAt, String question, AgentMode mode) {
        try {
            // 命中就统一标成"来自缓存"（缓存实现只管存取，不负责这件事）
            return answerCache.get(repoId, indexedAt, question, mode.name(), llmClient.model(), resolvedEngine())
                    .map(AgentAnswer::asCached)
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("读答案缓存失败（按未命中处理）：{}", e.toString());
            return null;
        }
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
