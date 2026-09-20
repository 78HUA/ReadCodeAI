package com.readcodeai.evidence;

import com.readcodeai.agent.AnswerService;
import com.readcodeai.agent.AgentLoop;
import com.readcodeai.agent.ScriptedLlmClient;
import com.readcodeai.agent.ToolRegistry;
import com.readcodeai.agent.model.AnsweredBy;
import com.readcodeai.agent.model.AskAnswer;
import com.readcodeai.agent.model.AskEvidence;
import com.readcodeai.agent.model.StopReason;
import com.readcodeai.agent.model.SupportCheck;
import com.readcodeai.config.BudgetGuard;
import com.readcodeai.config.LlmClient;
import com.readcodeai.config.ReadCodeAiProperties;
import com.readcodeai.index.ProjectIndexer;
import com.readcodeai.index.store.SymbolQueryRepository;
import com.readcodeai.retrieve.ContextSelector;
import com.readcodeai.retrieve.QueryRouter;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.TextRetriever;
import com.readcodeai.retrieve.model.RepoView;
import com.readcodeai.retrieve.model.SymbolView;
import com.readcodeai.verify.TestCorpus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ③ 层核验（这段代码**支持**这条结论吗）的验收。
 *
 * <p>这一层与 ①② 层的差别全在**它可能失败**：①② 是程序化比对，结论确定；
 * 这里是一次模型判定，可能判错、可能输出坏掉。所以用例分两组：
 * <ul>
 *   <li><b>判定本身</b>（离线脚本模型）：三种判定怎么映射、材料从哪儿来、判定失败算不算通过</li>
 *   <li><b>判定接入问答之后</b>：标成不支持时答案还在不在（mark）、要不要拒答（reject）、
 *       多跳路径上终止原因对不对</li>
 * </ul>
 *
 * <p>第三条在别处很容易被写成"没核验 = 通过"，这里专门钉住：
 * {@code UNAVAILABLE}（判定没做成）与 {@code NOT_CHECKED}（没做判定）都不是通过。
 */
@SpringBootTest
class SupportCheckerTest {

    /** 一被调用就抛错：用来证明"关闭时模型确实没被叫"。 */
    private static final ScriptedLlmClient.Script MUST_NOT_BE_CALLED = (turn, prompt) -> {
        throw new AssertionError("③ 层已关闭，不该调用模型，却在第 " + turn + " 轮被调用了");
    };

    @Autowired
    private TextRetriever textRetriever;

    @Autowired
    private SymbolQueryService queries;

    @Autowired
    private QueryRouter queryRouter;

    @Autowired
    private ContextSelector contextSelector;

    @Autowired
    private EvidenceVerifier evidenceVerifier;

    @Autowired
    private EvidenceRepair evidenceRepair;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private SymbolQueryRepository repository;

    @Autowired
    private ProjectIndexer indexer;

    @Autowired
    private ReadCodeAiProperties properties;

    @TempDir
    Path repoRoot;

    // ---- 一、判定本身 ----

    @Test
    void verdictsAreMappedFaithfully() {
        assertThat(verdictOf("{\"verdict\":\"supported\",\"reason\":\"片段就是这个方法的实现\"}").status())
                .isEqualTo(SupportCheck.Status.SUPPORTED);
        assertThat(verdictOf("{\"verdict\":\"unsupported\",\"reason\":\"片段是订单分页，结论说的是菜品\"}").status())
                .isEqualTo(SupportCheck.Status.UNSUPPORTED);
        assertThat(verdictOf("{\"verdict\":\"unknown\",\"reason\":\"片段太短\"}").status())
                .isEqualTo(SupportCheck.Status.UNCERTAIN);
        // 没约定的词不许猜：它是"判定没做成"，绝不是"通过"
        assertThat(verdictOf("{\"verdict\":\"probably\",\"reason\":\"大概是吧\"}").status())
                .isEqualTo(SupportCheck.Status.UNAVAILABLE);
        assertThat(verdictOf("{\"reason\":\"忘了给结论\"}").status())
                .isEqualTo(SupportCheck.Status.UNAVAILABLE);
    }

    @Test
    void markdownFencesAndExtraFieldsAreTolerated() {
        // 小模型很爱把 JSON 包在代码块里，或者多给一两个字段 —— 那是排版差异，不是判定失败
        SupportCheck check = verdictOf("""
                ```json
                {"verdict":"supported","reason":"对得上","confidence":0.9}
                ```""");
        assertThat(check.status()).isEqualTo(SupportCheck.Status.SUPPORTED);
        assertThat(check.reason()).isEqualTo("对得上");
    }

    @Test
    void judgementIsMadeOnTheRealCodeOnDiskNotOnTheModelsCopy() throws IOException {
        // 磁盘上的第 2、3 行是这两句；模型给的 snippet 是它自己抄的（这里是错的）
        Files.writeString(repoRoot.resolve("Sample.java"),
                "class Sample {\n  void realMethod() { call(); }\n  // 真实注释\n}\n",
                StandardCharsets.UTF_8);
        AskEvidence cited = new AskEvidence("Sample.java", 2, 3, "void 模型抄错的方法() { }", "证据");

        ScriptedLlmClient client = ScriptedLlmClient.lines("{\"verdict\":\"supported\",\"reason\":\"ok\"}");
        new ModelSupportChecker(client, false).check("问题", "结论", List.of(cited), repoRoot);

        String prompt = client.lastPrompt();
        assertThat(prompt).as("判定的依据必须是磁盘原文").contains("void realMethod()");
        assertThat(prompt).as("模型抄的片段不该出现在判定材料里（那是被核验的对象，不是依据）")
                .doesNotContain("模型抄错的方法");
        assertThat(prompt).as("还要带上问题与结论，判定才知道该对什么")
                .contains("问题").contains("结论");
    }

    @Test
    void locationThatCannotBeReadIsStatedInsteadOfSilentlyDropped() {
        ScriptedLlmClient client = ScriptedLlmClient.lines("{\"verdict\":\"unknown\",\"reason\":\"看不到代码\"}");
        new ModelSupportChecker(client, false).check("问题", "结论",
                List.of(new AskEvidence("不存在.java", 1, 5, "", "证据")), repoRoot);

        assertThat(client.lastPrompt()).as("读不到就明说读不到，不能悄悄少给一段材料").contains("读不到代码");
    }

    @Test
    void aFailedJudgementIsNotASilentPass() {
        LlmClient boom = new ScriptedLlmClient((turn, prompt) -> {
            throw new IllegalStateException("模型接口 500");
        });
        SupportCheck check = new ModelSupportChecker(boom, false)
                .check("问题", "结论", List.of(new AskEvidence("A.java", 1, 1, "", "x")), repoRoot);

        assertThat(check.status()).isEqualTo(SupportCheck.Status.UNAVAILABLE);
        assertThat(check.checked()).as("判定没做成，不能算做过判定").isFalse();
        assertThat(check.flagged()).isFalse();
        assertThat(check.describe()).contains("未完成");

        LlmClient garbage = ScriptedLlmClient.lines("我不是 JSON");
        assertThat(new ModelSupportChecker(garbage, false)
                .check("问题", "结论", List.of(new AskEvidence("A.java", 1, 1, "", "x")), repoRoot)
                .status())
                .as("输出不是 JSON 同样是判定没做成").isEqualTo(SupportCheck.Status.UNAVAILABLE);
    }

    @Test
    void whenDisabledTheModelIsNeverCalled() {
        ScriptedLlmClient client = new ScriptedLlmClient(MUST_NOT_BE_CALLED);
        SupportCheck check = new NoopSupportChecker("readcodeai.verify.support-check=off")
                .check("问题", "结论", List.of(new AskEvidence("A.java", 1, 1, "", "x")), repoRoot);

        assertThat(client.calls()).isZero();
        assertThat(check.status()).isEqualTo(SupportCheck.Status.NOT_CHECKED);
        assertThat(check.checked()).as("「没查」绝不能读成「查过没问题」").isFalse();
        assertThat(check.reason()).contains("support-check=off");
    }

    // ---- 二、判定接入问答之后 ----

    @Test
    void unsupportedVerdictIsMarkedButTheAnswerSurvives() {
        // 默认模式 mark：判定说不支持，答案仍然返回，但必须显著标出来（连带理由）
        AskAnswer answer = askWith("unsupported", "片段是订单分页，结论说的是菜品分页", false);

        assertThat(answer.refused()).as("mark 模式不丢答案").isFalse();
        assertThat(answer.evidence()).isNotEmpty();
        SupportCheck check = answer.verification().support();
        assertThat(check.status()).isEqualTo(SupportCheck.Status.UNSUPPORTED);
        assertThat(check.flagged()).isTrue();
        assertThat(check.reason()).contains("菜品");
        assertThat(check.tokens()).as("判定自己花的 token 要单独记下来").isGreaterThan(0);
    }

    @Test
    void unsupportedVerdictRejectsWhenConfiguredTo() {
        AskAnswer answer = askWith("unsupported", "片段是订单分页，结论说的是菜品分页", true);

        assertThat(answer.refused()).as("reject 模式按拒答处理").isTrue();
        assertThat(answer.refusalReason()).contains("③ 层").contains("菜品");
        assertThat(answer.evidence()).as("拒答也要把被引的证据交出来，使用者才能自己看").isNotEmpty();
        assertThat(answer.verification().support().status()).isEqualTo(SupportCheck.Status.UNSUPPORTED);
    }

    @Test
    void supportedVerdictLeavesEverythingAsBefore() {
        AskAnswer answer = askWith("supported", "片段正是这个方法的实现", false);

        assertThat(answer.refused()).isFalse();
        assertThat(answer.verification().support().status()).isEqualTo(SupportCheck.Status.SUPPORTED);
        assertThat(answer.verification().support().flagged()).isFalse();
        // 判定用的 token **不并进**生成用量：两笔账要能分开说
        assertThat(answer.promptTokens()).isLessThan(answer.verification().support().tokens());
    }

    @Test
    void refusalCarriesNoJudgement() {
        RepoView repo = corpus();
        SymbolView target = mostCalled(repo.id());
        // 模型说"材料不够，拒答" —— 没有结论可判，③ 层不该白花一次调用
        ScriptedLlmClient client = ScriptedLlmClient.lines(
                ScriptedLlmClient.singleHopRefuse("给的材料里没有这个方法"));

        AskAnswer answer = answerServiceWith(client, new ModelSupportChecker(client, false))
                .ask(repo.id(), "这个方法大致是做什么的：" + target.qualifiedName() + "？", null, 8);

        assertThat(answer.refused()).isTrue();
        assertThat(client.calls()).as("拒答不该触发 ③ 层判定").isEqualTo(1);
        assertThat(answer.verification().support().status()).isEqualTo(SupportCheck.Status.NOT_CHECKED);
    }

    @Test
    void staticAnswersAreNotJudged() {
        RepoView repo = corpus();
        SymbolView target = firstWithCallers(repo.id());
        ScriptedLlmClient client = new ScriptedLlmClient(MUST_NOT_BE_CALLED);

        AskAnswer answer = answerServiceWith(client, new ModelSupportChecker(client, false))
                .ask(repo.id(), "谁调用了 " + target.qualifiedName() + "？", null, 8);

        assertThat(answer.answeredBy()).isEqualTo(AnsweredBy.STATIC);
        assertThat(client.calls()).as("静态路线既不生成也不判定").isZero();
        assertThat(answer.verification().support().status()).isEqualTo(SupportCheck.Status.NOT_CHECKED);
        assertThat(answer.verification().support().reason()).contains("静态路线");
    }

    @Test
    void multiHopStopsWithTheSupportReasonWhenRejecting() {
        RepoView repo = corpus();
        SymbolView target = firstWithCallers(repo.id());
        // 结论引的必须是**轨迹里查过的位置**，否则会先被"引用要落在轨迹里"那条规则拦下，
        // 测到的就不是 ③ 层了（findCallers 查到的就是这些调用点）
        var caller = queries.callers(target.id()).stream()
                .filter(call -> call.symbolId() != null)
                .findFirst()
                .orElseThrow();
        ScriptedLlmClient client = ScriptedLlmClient.lines(
                ScriptedLlmClient.callTool("findCallers", "symbol", target.qualifiedName()),
                ScriptedLlmClient.answer("上游一共这么几处", caller.callSiteFile(), caller.callLine(),
                        caller.callLine(), null),
                "{\"verdict\":\"unsupported\",\"reason\":\"引的是另一个方法的调用点\"}");
        AgentLoop loop = new AgentLoop(toolRegistry, evidenceVerifier,
                new ModelSupportChecker(client, true), client, 2);

        var answer = loop.run(repo.id(), Path.of(repo.rootPath()),
                "谁调用了 " + target.qualifiedName() + "？间接的也要。",
                AgentLoop.Seeds.none(), new BudgetGuard(8, 60_000, 1_000_000, 100, 0, 0));

        assertThat(answer.refused()).isTrue();
        assertThat(answer.stopReason()).isEqualTo(StopReason.SUPPORT_REJECTED);
        assertThat(answer.reason()).contains("③ 层").contains("另一个方法");
        assertThat(answer.verification().support().status()).isEqualTo(SupportCheck.Status.UNSUPPORTED);
        assertThat(answer.steps()).as("拒答也要留下轨迹，否则无从复核").isNotEmpty();
    }

    // ---- helpers ----

    private SupportCheck verdictOf(String modelOutput) {
        ScriptedLlmClient client = ScriptedLlmClient.lines(modelOutput);
        return new ModelSupportChecker(client, false)
                .check("问题", "结论", List.of(new AskEvidence("A.java", 1, 1, "", "x")), repoRoot);
    }

    /**
     * 走一遍单跳问答：脚本模型第 1 轮给结论，第 2 轮给 ③ 层判定。
     *
     * @param verdict 判定结果（supported / unsupported / unknown）
     * @param reject  配置成 reject 模式还是 mark 模式
     */
    private AskAnswer askWith(String verdict, String reason, boolean reject) {
        RepoView repo = corpus();
        SymbolView target = mostCalled(repo.id());
        ScriptedLlmClient client = ScriptedLlmClient.lines(
                ScriptedLlmClient.singleHopAnswer("这个方法的实现看这里", target.filePath(),
                        target.startLine(), target.endLine(), null),
                "{\"verdict\":\"" + verdict + "\",\"reason\":\"" + reason + "\"}")
                .withTokenUsage(200, 100);
        return answerServiceWith(client, new ModelSupportChecker(client, reject))
                .ask(repo.id(), "这个方法大致是做什么的：" + target.qualifiedName() + "？", null, 8);
    }

    private AnswerService answerServiceWith(LlmClient client, SupportChecker checker) {
        return new AnswerService(textRetriever, queries, queryRouter, contextSelector,
                evidenceVerifier, evidenceRepair, checker, client, properties);
    }

    private RepoView corpus() {
        var repo = TestCorpus.resolve(indexer, queries);
        assumeTrue(repo.isPresent(), "语料不存在（sample-repos 或 -Dreadcodeai.verify.repo=），跳过");
        assumeTrue(!"UNAVAILABLE".equals(repo.get().status()), "语料索引不可用，跳过");
        return repo.get();
    }

    private SymbolView mostCalled(long repoId) {
        List<SymbolView> candidates = repository.mostCalledMethods(repoId, 20);
        assumeTrue(!candidates.isEmpty(), "语料里没有可用的方法符号，跳过");
        return candidates.get(0);
    }

    private SymbolView firstWithCallers(long repoId) {
        return repository.mostCalledMethods(repoId, 20).stream()
                .filter(symbol -> !queries.callers(symbol.id()).isEmpty())
                .findFirst()
                .orElseGet(() -> {
                    assumeTrue(false, "语料里没有被调用的方法，跳过");
                    throw new IllegalStateException("unreachable");
                });
    }
}
