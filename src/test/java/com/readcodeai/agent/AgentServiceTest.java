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
        ScriptedLlmClient client = ScriptedLlmClient.lines(
                ScriptedLlmClient.callTool("findCallers", "symbol", target.qualifiedName()),
                ScriptedLlmClient.answer("上游查到 " + plan.size() + " 个直接调用者", target.filePath(),
                        target.startLine(), target.endLine(), null));

        // 题面里既有「谁调用」（会命中确定性路由），又有「间接」（单跳答不了）
        AgentAnswer answer = serviceWith(client)
                .ask(repo.id(), "谁调用了 " + target.qualifiedName() + "？间接的也要。",
                        AgentMode.MULTI_HOP, null, 8);

        assertThat(client.calls()).as("必须真的走了多跳（模型被调用）").isGreaterThanOrEqualTo(2);
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
                new AgentLoop(toolRegistry, evidenceVerifier, com.readcodeai.verify.TestCheckers.NONE, new NoopLlmClient("测试：未配置"), 2),
                queryRouter, queries, new NoopLlmClient("测试：未配置"),
                new com.readcodeai.agent.cache.NoopAnswerCache("测试"), properties,
                com.readcodeai.verify.TestAnswerLogs.silent(properties));
        RepoView repo = corpus();

        assertThatThrownBy(() -> service.ask(repo.id(), "这个参数是从哪来的？", AgentMode.MULTI_HOP, null, 8))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("多跳检索不可用")
                .hasMessageContaining("确定性能力不受影响");
    }

    /**
     * 整套流水线都换成脚本模型：**单跳路径也要**。
     *
     * <p>踩过一次坑：只把脚本模型喂给多跳循环，单跳那条路仍然用容器里注入的 Noop 客户端，
     * 于是"单跳模式"的测试直接报"未配置 LLM" —— 测试自己搭的架子，必须两处一起换。
     */
    private AgentService serviceWith(LlmClient client) {
        AnswerService singleHop = new AnswerService(textRetriever, queries, queryRouter, contextSelector,
                evidenceVerifier, evidenceRepair, com.readcodeai.verify.TestCheckers.NONE, client, properties,
                com.readcodeai.verify.TestAnswerLogs.silent(properties));
        return new AgentService(singleHop, new AgentLoop(toolRegistry, evidenceVerifier, com.readcodeai.verify.TestCheckers.NONE, client, 2),
                queryRouter, queries, client, new com.readcodeai.agent.cache.NoopAnswerCache("测试"),
                properties, com.readcodeai.verify.TestAnswerLogs.silent(properties));
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
