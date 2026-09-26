package com.readcodeai.agent;

import com.readcodeai.agent.model.AgentAnswer;
import com.readcodeai.agent.model.AgentMode;
import com.readcodeai.agent.model.AnsweredBy;
import com.readcodeai.agent.model.StopReason;
import com.readcodeai.config.LlmClient;
import com.readcodeai.config.NoopLlmClient;
import com.readcodeai.config.ReadCodeAiProperties;
import com.readcodeai.evidence.EvidenceRepair;
import com.readcodeai.evidence.EvidenceVerifier;
import com.readcodeai.retrieve.ContextSelector;
import com.readcodeai.retrieve.TextRetriever;
import com.readcodeai.eval.ChainQuestionGenerator;
import com.readcodeai.index.ProjectIndexer;
import com.readcodeai.index.store.SymbolQueryRepository;
import com.readcodeai.retrieve.QueryRouter;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.RepoView;
import com.readcodeai.retrieve.model.SymbolView;
import com.readcodeai.verify.TestCorpus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 三岔路口的验收：**同一个问题，什么时候不经模型、什么时候单跳、什么时候多跳**。
 *
 * <p>这条判断是「能算准的别猜」的最后一道关口 —— 判错了的代价是不对称的：
 * 把确定性问题交给模型（把确定性换成不确定性），比多花一次检索严重得多。
 *
 * <p>所以这里用**脚本模型 + "调用即失败"的守卫**来验证"确定性问题根本没叫模型"这件事：
 * 声称"省了一次模型调用"是没用的，得能证明它确实没被调用。
 */
@SpringBootTest
class AgentServiceTest {

    @Autowired
    private AnswerService answerService;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private EvidenceVerifier evidenceVerifier;

    @Autowired
    private EvidenceRepair evidenceRepair;

    @Autowired
    private TextRetriever textRetriever;

    @Autowired
    private ContextSelector contextSelector;

    @Autowired
    private QueryRouter queryRouter;

    @Autowired
    private SymbolQueryService queries;

    @Autowired
    private SymbolQueryRepository repository;

    @Autowired
    private ChainQuestionGenerator chainGenerator;

    @Autowired
    private ProjectIndexer indexer;

    @Autowired
    private ReadCodeAiProperties properties;

    @Autowired
    private SummaryAnswerer summaryAnswerer;

    /** 一被调用就抛错 —— 用来证明"这条路上模型确实没被叫"（而不是"我们以为没叫"）。 */
    private static final ScriptedLlmClient.Script MUST_NOT_BE_CALLED = (turn, prompt) -> {
        throw new AssertionError("这条路线不该调用模型，却在第 " + turn + " 轮被调用了");
    };

    @Test
    void deterministicQuestionIsAnsweredWithoutCallingTheModel() {
        RepoView repo = corpus();
        SymbolView target = firstWithCallers(repo.id());

        AgentAnswer answer = serviceWith(new ScriptedLlmClient(MUST_NOT_BE_CALLED))
                .ask(repo.id(), "谁调用了 " + target.qualifiedName() + "？", AgentMode.MULTI_HOP, null, 8);

        assertThat(answer.answeredBy()).isEqualTo(AnsweredBy.STATIC);
        assertThat(answer.stopReason()).isEqualTo(StopReason.STATIC);
        assertThat(answer.steps()).as("确定性路线没有轨迹").isEmpty();
        assertThat(answer.refused()).isFalse();
        assertThat(answer.evidence()).isNotEmpty();
    }

    @Test
    void aChainQuestionGoesToTheAgentEvenWhenTheRouterCouldAnswerItInOneHop() {
        RepoView repo = corpus();
        SymbolView target = firstWithCallers(repo.id());
        List<String> plan = chainGenerator.bfsPlan(target.id(), 1);
        assumeTrue(!plan.isEmpty(), "语料里没有上游调用者，跳过");
        // 多跳引擎跑在脚本模型上：查一跳 → 给结论
        var stub = new com.readcodeai.agent.springai.ScriptedChatModel();
        stub.scriptLines(
                callFindCallers(target.qualifiedName()),
                com.readcodeai.agent.springai.ScriptedChatModel.text(
                        finalJson(target.filePath(), target.startLine(), target.endLine())));
        // 单跳那条路不该被走到（走到了就直接失败）
        ScriptedLlmClient client = new ScriptedLlmClient((t, prompt) -> {
            throw new AssertionError("这条用例应当走多跳");
        });

        // 题面里既有「谁调用」（会命中确定性路由），又有「间接」（单跳答不了）
        AgentAnswer answer = serviceWith(client, com.readcodeai.agent.springai.TestEngines.on(stub,
                        toolRegistry, evidenceVerifier, com.readcodeai.verify.TestCheckers.NONE, properties))
                .ask(repo.id(), "谁调用了 " + target.qualifiedName() + "？间接的也要。",
                        AgentMode.MULTI_HOP, null, 8);

        assertThat(stub.calls()).as("必须真的走了多跳（模型被调用）").isGreaterThanOrEqualTo(2);
        assertThat(answer.mode()).isEqualTo(AgentMode.MULTI_HOP);
        assertThat(answer.steps()).isNotEmpty();
        assertThat(answer.answeredBy()).isEqualTo(AnsweredBy.LLM);
        assertThat(answer.stopReason()).isEqualTo(StopReason.FINAL);
    }

    @Test
    void singleHopModeAnswersTheSameQuestionInOneRound() {
        RepoView repo = corpus();
        SymbolView target = firstWithCallers(repo.id());
        ScriptedLlmClient client = ScriptedLlmClient.lines(
                ScriptedLlmClient.singleHopAnswer("单跳只查了一次", target.filePath(),
                        target.startLine(), target.endLine(), null));

        // 问题里要带上真实的标识符：gson 是英文语料，纯中文问句检索不到任何片段，
        // 那样会在"检索为空"这一步就拒答（正确行为，但测不到我们想测的单跳路径）
        AgentAnswer answer = serviceWith(client)
                .ask(repo.id(), "这个方法大致是做什么的：" + target.qualifiedName() + "？",
                        AgentMode.SINGLE_HOP, null, 8);

        assertThat(answer.mode()).isEqualTo(AgentMode.SINGLE_HOP);
        assertThat(answer.stopReason()).isIn(StopReason.SINGLE_HOP, StopReason.STATIC);
        assertThat(answer.steps()).as("单跳没有轨迹").isEmpty();
        assertThat(answer.refused()).as("单跳要真的给出答案（这条断言以前是漏的，被假绿过）").isFalse();
        assertThat(client.calls()).as("单跳最多一次模型调用").isLessThanOrEqualTo(1);
    }

    @Test
    void multiHopRefusesToPretendWhenNoModelIsConfigured() {
        AgentService service = new AgentService(answerService,
                com.readcodeai.agent.springai.TestEngines.unavailable(
                        "未配置模型（spring.ai.openai.*），Spring AI 多跳引擎不可用；"
                                + "定位 / 调用关系 / 实现类 / 全文检索等确定性能力不受影响"),
                queryRouter, queries, new NoopLlmClient("测试：未配置"),
                new com.readcodeai.agent.cache.NoopAnswerCache("测试"), properties,
                com.readcodeai.verify.TestAnswerLogs.silent(properties), summaryAnswerer);
        RepoView repo = corpus();

        assertThatThrownBy(() -> service.ask(repo.id(), "这个参数是从哪来的？", AgentMode.MULTI_HOP, null, 8))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("多跳引擎不可用")
                .hasMessageContaining("确定性能力不受影响");
    }

    /**
     * 深链模式：**同一套记账，只把额度换大** —— 验收方式是"它真的多跑了几轮"。
     *
     * <p>量出来的依据：多跳实验里 2/3 的题是"轮次用尽"停的，而轨迹里已经有 60% / 100% 的命中 ——
     * 那些链不是答不了，是没查完。
     */
    @Test
    void deepModeRunsTheLargerBudgetOnlyWhenAsked() {
        RepoView repo = corpus();
        List<SymbolView> methods = repository.mostCalledMethods(repo.id(), 30);
        assumeTrue(methods.size() >= 14, "语料里方法太少，跑不满两套预算");
        String[] symbols = methods.stream().limit(14).map(SymbolView::qualifiedName).toArray(String[]::new);

        // 脚本模型每轮去查一个**不同**的符号：永远不给结论，直到预算把它掐停。
        // 换着符号查是为了不触发"重复调用"的环检测 —— 那会让它提前停，就量不到预算差异了
        com.readcodeai.agent.springai.ScriptedChatModel stub = new com.readcodeai.agent.springai.ScriptedChatModel();
        java.util.concurrent.atomic.AtomicInteger turn = new java.util.concurrent.atomic.AtomicInteger();
        stub.script(prompt -> com.readcodeai.agent.springai.ScriptedChatModel.toolCall("findDefinition",
                "{\"symbol\":\"" + symbols[turn.getAndIncrement() % symbols.length] + "\"}"));
        // 单跳那条路不该被走到：真走到了就当场失败，别让它悄悄换个引擎继续跑
        AgentService service = serviceWith(new ScriptedLlmClient((t, prompt) -> {
            throw new AssertionError("这条用例问的是链式问题，不该走单跳");
        }), com.readcodeai.agent.springai.TestEngines.on(stub, toolRegistry, evidenceVerifier,
                com.readcodeai.verify.TestCheckers.NONE, properties));

        String question = "这个参数是从哪来的：" + symbols[0] + "？";
        AgentAnswer normal = service.ask(repo.id(), question, AgentMode.MULTI_HOP, null, 8, false);
        AgentAnswer deep = service.ask(repo.id(), question, AgentMode.MULTI_HOP, null, 8, true);

        assertThat(normal.stopReason()).isEqualTo(StopReason.BUDGET_ROUNDS);
        assertThat(deep.stopReason()).isEqualTo(StopReason.BUDGET_ROUNDS);
        // 轮次算术：模型实际被调用 maxRounds-1 次 —— 最后那"1 轮"是留给**结论**的，
        // 而模型在被判为最后一轮时还想着调工具，就直接停机（见 BudgetToolCallingManager 与配置里的注释）
        assertThat(normal.rounds()).isEqualTo(properties.getLlm().getMaxRounds() - 1);
        assertThat(deep.rounds()).as("深链模式应当跑满 deep 那套额度")
                .isEqualTo(properties.getLlm().getDeep().getMaxRounds() - 1);
        assertThat(deep.rounds()).isGreaterThan(normal.rounds());
    }

    /**
     * 整套流水线都换成脚本模型：**单跳路径也要**。
     *
     * <p>踩过一次坑：只把脚本模型喂给多跳循环，单跳那条路仍然用容器里注入的 Noop 客户端，
     * 于是"单跳模式"的测试直接报"未配置 LLM" —— 测试自己搭的架子，必须两处一起换。
     */
    /** 走单跳 / 确定性路线的那批用例：多跳引擎**不该被调用到**，用替身占位（调到了就直接失败）。 */
    private AgentService serviceWith(LlmClient client) {
        return serviceWith(client, com.readcodeai.agent.springai.TestEngines.unused());
    }

    private AgentService serviceWith(LlmClient client, AgentEngine engine) {
        AnswerService singleHop = new AnswerService(textRetriever, queries, queryRouter, contextSelector,
                evidenceVerifier, evidenceRepair, com.readcodeai.verify.TestCheckers.NONE, client, properties,
                com.readcodeai.verify.TestAnswerLogs.silent(properties));
        return new AgentService(singleHop, engine,
                queryRouter, queries, client, new com.readcodeai.agent.cache.NoopAnswerCache("测试"),
                properties, com.readcodeai.verify.TestAnswerLogs.silent(properties), summaryAnswerer);
    }

    private static org.springframework.ai.chat.model.ChatResponse callFindCallers(String symbol) {
        return com.readcodeai.agent.springai.ScriptedChatModel.toolCall("findCallers",
                "{\"symbol\":\"" + symbol + "\"}");
    }

    /** 一条合法的结论 JSON（不写 snippet：只核验文件与行号）。 */
    private static String finalJson(String file, int startLine, int endLine) {
        return """
                {"final":{"answer":"上游调用链已查完。","evidence":[{"file":"%s","startLine":%d,"endLine":%d,                "snippet":"","why":"工具查到的位置"}],"refused":false,"refusalReason":""}}"""
                .formatted(file, startLine, endLine);
    }

    private SymbolView firstWithCallers(long repoId) {
        List<SymbolView> candidates = repository.mostCalledMethods(repoId, 20);
        return candidates.stream()
                .filter(symbol -> !queries.callers(symbol.id()).isEmpty())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("语料里找不到有调用点的符号"));
    }

    private RepoView corpus() {
        var resolved = TestCorpus.resolve(indexer, queries);
        assumeTrue(resolved.isPresent(), "语料 " + TestCorpus.SAMPLE + " 不存在，跳过");
        return resolved.get();
    }
}
