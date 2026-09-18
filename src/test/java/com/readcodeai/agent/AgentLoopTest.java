package com.readcodeai.agent;

import com.readcodeai.agent.model.AgentAnswer;
import com.readcodeai.agent.model.AgentStep;
import com.readcodeai.agent.model.StopReason;
import com.readcodeai.config.BudgetGuard;
import com.readcodeai.config.LlmClient;
import com.readcodeai.eval.ChainQuestionGenerator;
import com.readcodeai.evidence.EvidenceVerifier;
import com.readcodeai.index.ProjectIndexer;
import com.readcodeai.index.store.SymbolQueryRepository;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.RepoView;
import com.readcodeai.retrieve.model.SymbolView;
import com.readcodeai.verify.TestCorpus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 多跳循环的**机制验证**：用脚本化的假模型，把每一条兜底路径单独走一遍。
 *
 * <p>要证明的六件事（每件对应一个用例）：
 * <ol>
 *   <li>正常链：能沿调用图一跳一跳走完，结论带**通过核验**的证据</li>
 *   <li>环检测：重复的边不再执行，模型被明确告知"这个查过了"</li>
 *   <li>无进展：连续重复就主动终止 —— 不终止的话它会一直烧预算</li>
 *   <li>预算：轮次耗尽时在**有限轮内**停下，且已查到的轨迹不丢</li>
 *   <li>证据：结论的证据对不上磁盘 → 退回重发一次 → 仍不行就拒答</li>
 *   <li>格式：模型胡言乱语 → 重发一次提示 → 仍不行就明确归类为格式错误，不崩</li>
 * </ol>
 *
 * <p><b>为什么用假模型测机制</b>：这些是确定性逻辑，用真模型测就变成"网络好的时候能测、
 * 每次花钱、还不可复现"。真实模型的效果数字在 {@code MultiHopLiveTest} 里单独跑、单独报。
 */
@SpringBootTest
class AgentLoopTest {

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private EvidenceVerifier evidenceVerifier;

    @Autowired
    private SymbolQueryService queries;

    @Autowired
    private SymbolQueryRepository repository;

    @Autowired
    private ChainQuestionGenerator chainGenerator;

    @Autowired
    private ProjectIndexer indexer;

    @Test
    void walksTheChainAndAnswersWithVerifiedEvidence() {
        RepoView repo = corpus();
        SymbolView target = firstWithCallers(repo.id());
        List<String> plan = chainGenerator.bfsPlan(target.id(), 2);
        assumeTrue(plan.size() >= 2, "语料里没有两跳以上的调用链，跳过");
        // 理想模型的追问顺序：先从问题里的符号起步，再按 BFS 顺序逐跳上溯
        String[] script = concat(
                concat(new String[]{ScriptedLlmClient.callTool("findCallers", "symbol", target.qualifiedName())},
                        plan.stream().map(name -> ScriptedLlmClient.callTool("findCallers", "symbol", name))
                                .toArray(String[]::new)),
                ScriptedLlmClient.answer("按脚本走完了调用链", target.filePath(),
                        target.startLine(), target.endLine(), null));

        AgentAnswer answer = loopWith(ScriptedLlmClient.lines(script))
                .run(repo.id(), root(repo), "有哪些方法最终会调用 " + target.qualifiedName() + "？",
                        List.of(), budget(plan.size() + 3));

        System.out.printf("%n[离线多跳] 计划 %d 跳 · 实际 %d 跳 · 轮次 %d · 终止 %s · 发现 %d 个上游符号 · 证据 %d 条通过核验%n",
                plan.size() + 1, answer.toolCalls(), answer.rounds(), answer.stopReason(),
                answer.subjectsFound().size(), answer.verification().verified());
        if (answer.refused()) {
            System.out.println("  拒答原因：" + answer.reason());
        }

        assertThat(answer.refused()).isFalse();
        assertThat(answer.stopReason()).isEqualTo(StopReason.FINAL);
        assertThat(answer.steps()).hasSize(plan.size() + 1);
        assertThat(answer.subjectsFound())
                .as("走完静态算出的链路后，上游符号集合应当覆盖真值（这是计量口径的自检）")
                .containsAll(chainGenerator.transitiveCallers(target.id(), 2));
        assertThat(answer.verification().verified()).isEqualTo(1);
        assertThat(answer.repeatedCalls()).isZero();
    }

    @Test
    void blocksTheSecondIdenticalCallAndTellsTheModelWhy() {
        RepoView repo = corpus();
        SymbolView target = firstWithCallers(repo.id());
        String call = ScriptedLlmClient.callTool("findCallers", "symbol", target.qualifiedName());

        AgentAnswer answer = loopWith(ScriptedLlmClient.lines(call, call,
                ScriptedLlmClient.answer("查到上游后收工", target.filePath(),
                        target.startLine(), target.endLine(), null)))
                .run(repo.id(), root(repo), "谁调用了它", List.of(), budget(5));

        List<AgentStep> steps = answer.steps();
        assertThat(steps).as("两次工具轮（一次执行、一次被拦下）").hasSize(2);
        assertThat(steps.get(1).repeated()).as("第二次相同调用必须被环检测拦下").isTrue();
        assertThat(steps.get(1).observation()).contains("已经做过");
        assertThat(answer.toolCalls()).as("真正执行的查询只有一次").isEqualTo(1);
        assertThat(answer.repeatedCalls()).isEqualTo(1);
        assertThat(answer.refused()).as("被拦一次之后仍能继续干活、最终给出结论").isFalse();
    }

    @Test
    void stopsWhenTheModelKeepsGoingInCircles() {
        RepoView repo = corpus();
        SymbolView target = firstWithCallers(repo.id());
        String call = ScriptedLlmClient.callTool("findCallers", "symbol", target.qualifiedName());

        AgentAnswer answer = loopWith(ScriptedLlmClient.lines(call, call, call, call))
                .run(repo.id(), root(repo), "谁调用了它", List.of(), budget(10));

        assertThat(answer.refused()).isTrue();
        assertThat(answer.stopReason()).isEqualTo(StopReason.NO_PROGRESS);
        assertThat(answer.reason()).contains("绕圈");
        assertThat(answer.rounds())
                .as("第 1 轮真查、后 3 轮连续重复即停，不会把 10 轮预算烧完").isEqualTo(4);
    }

    @Test
    void stopsAtTheRoundBudgetAndKeepsThePartOfTheChainItAlreadyFound() {
        RepoView repo = corpus();
        SymbolView target = firstWithCallers(repo.id());
        List<String> plan = chainGenerator.bfsPlan(target.id(), 3);
        assumeTrue(plan.size() >= 4, "语料里没有足够长的调用链，跳过");
        // 第 1 跳从问题里的符号起步（这一跳必定查到东西），后面才是往上追
        String[] script = concat(
                new String[]{ScriptedLlmClient.callTool("findCallers", "symbol", target.qualifiedName())},
                plan.stream().map(name -> ScriptedLlmClient.callTool("findCallers", "symbol", name))
                        .toArray(String[]::new));

        // 轮次上限 5：前 3 轮真查，第 4 轮**留给结论**（模型却还在要求查工具）→ 直接停机
        AgentAnswer answer = loopWith(ScriptedLlmClient.lines(script))
                .run(repo.id(), root(repo), "完整调用链", List.of(), budget(5));

        assertThat(answer.refused()).isTrue();
        assertThat(answer.stopReason()).isEqualTo(StopReason.BUDGET_ROUNDS);
        assertThat(answer.rounds()).as("第 4 轮是保留给结论的，模型却要查工具 → 用掉 4 轮后停机").isEqualTo(4);
        assertThat(answer.steps()).hasSize(3);
        assertThat(answer.evidence())
                .as("停机时轨迹里查到的证据必须一并交出（有材料 ≠ 有结论，但材料不能丢）")
                .isNotEmpty();
        assertThat(answer.budgetStopped()).isTrue();
    }

    @Test
    void stopsWhenTheTokenBudgetIsExhausted() {
        RepoView repo = corpus();
        SymbolView target = firstWithCallers(repo.id());
        List<String> plan = chainGenerator.bfsPlan(target.id(), 3);
        assumeTrue(plan.size() >= 4, "语料里没有足够长的调用链，跳过");

        ScriptedLlmClient client = ScriptedLlmClient.lines(plan.stream()
                        .map(name -> ScriptedLlmClient.callTool("findCallers", "symbol", name))
                        .toArray(String[]::new))
                .withTokenUsage(1_000, 0);
        BudgetGuard budget = new BudgetGuard(10, 60_000, 2_500, 100, 0, 0);

        AgentAnswer answer = loopWith(client).run(repo.id(), root(repo), "完整调用链", List.of(), budget);

        assertThat(answer.stopReason()).isEqualTo(StopReason.BUDGET_TOKENS);
        assertThat(answer.rounds()).as("每次 1000 token、上限 2500 → 第 3 次之后触顶").isEqualTo(3);
    }

    @Test
    void rejectsAnAnswerWhoseEvidenceDoesNotMatchTheDisk() {
        RepoView repo = corpus();

        AgentAnswer answer = loopWith(ScriptedLlmClient.lines(
                ScriptedLlmClient.answer("我编的", "src/does/not/Exist.java", 1, 2, "public void nope()"),
                ScriptedLlmClient.answer("我还是编的", "src/does/not/Exist.java", 1, 2, "public void nope()")))
                .run(repo.id(), root(repo), "随便问问", List.of(), budget(5));

        assertThat(answer.refused()).isTrue();
        assertThat(answer.stopReason()).isEqualTo(StopReason.EVIDENCE_REJECTED);
        assertThat(answer.reason()).contains("核验");
        assertThat(answer.rounds())
                .as("退回重发一次（共两轮），而不是无限重试").isEqualTo(2);
    }

    @Test
    void survivesGarbageFromTheModelWithoutCrashing() {
        RepoView repo = corpus();

        AgentAnswer answer = loopWith(ScriptedLlmClient.lines(
                "抱歉，我不能这么做。",
                "总之就是不能。" + System.lineSeparator() + "真的不能。"))
                .run(repo.id(), root(repo), "随便问问", List.of(), budget(5));

        assertThat(answer.refused()).isTrue();
        assertThat(answer.stopReason()).isEqualTo(StopReason.FORMAT_ERROR);
        assertThat(answer.rounds()).as("重发一次提示后仍不合法就停").isEqualTo(2);
        assertThat(answer.evidence()).isEmpty();
    }

    @Test
    void refusesWhenTheModelGivesAConclusionWithoutEvidence() {
        RepoView repo = corpus();

        AgentAnswer answer = loopWith(ScriptedLlmClient.lines(
                "{\"thought\":\"够了\",\"final\":{\"answer\":\"我觉得是这样\",\"evidence\":[],\"refused\":false}}"))
                .run(repo.id(), root(repo), "随便问问", List.of(), budget(5));

        assertThat(answer.refused()).isTrue();
        assertThat(answer.stopReason()).isEqualTo(StopReason.NO_EVIDENCE);
        assertThat(answer.reason()).contains("证据");
    }

    // ---- helpers ----

    private AgentLoop loopWith(LlmClient client) {
        return new AgentLoop(toolRegistry, evidenceVerifier, client);
    }

    private static BudgetGuard budget(int rounds) {
        return new BudgetGuard(rounds, 60_000, 1_000_000, 100, 0, 0);
    }

    private static Path root(RepoView repo) {
        return Path.of(repo.rootPath());
    }

    private static String[] concat(String[] first, String... more) {
        List<String> all = new ArrayList<>(List.of(first));
        all.addAll(List.of(more));
        return all.toArray(String[]::new);
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
