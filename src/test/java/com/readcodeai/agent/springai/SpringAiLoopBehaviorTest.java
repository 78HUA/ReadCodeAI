package com.readcodeai.agent.springai;

import com.readcodeai.agent.model.AgentAnswer;
import com.readcodeai.agent.model.AgentSeeds;
import com.readcodeai.agent.model.AgentStep;
import com.readcodeai.agent.model.AskEvidence;
import com.readcodeai.agent.model.StopReason;
import com.readcodeai.config.BudgetGuard;
import com.readcodeai.eval.ChainQuestionGenerator;
import com.readcodeai.index.ProjectIndexer;
import com.readcodeai.index.store.SymbolQueryRepository;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.RepoView;
import com.readcodeai.retrieve.model.SymbolView;
import com.readcodeai.verify.TestCorpus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * **默认引擎（Spring AI）的循环机制验证**：逐条对应手写版 {@code AgentLoopTest} 的 9 条兜底用例。
 *
 * <p>为什么要搬而不是直接删：循环的四类约束是"能用的系统"与 demo 的分界，
 * 而它们的守门测试原先**全部挂在手写引擎上**（Spring AI 侧只有 3 条）——
 * 手写版一旦退役，默认引擎的这几条行为就再没人守，CI 还会一路绿。
 *
 * <p>搬的过程本身找出了**三处真实差异**（都已补齐，见各自的注释）：
 * <ol>
 *   <li>被环检测拦下的那一跳**没进轨迹** → 界面看不出"模型在这里被挡过一次"；</li>
 *   <li>**没有"连续重复即判绕圈"的主动终止** → 只能等轮次烧完，且会被误归因成"轮次预算耗尽"；</li>
 *   <li>**结论轮与重发轮的轮次/token 没记账**（框架只在有工具调用那轮走 {@code ToolCallingManager}）
 *       → 运行统计与预算依据都比实际偏小。</li>
 * </ol>
 *
 * <p>③ 层在本类里**关掉**（{@code readcodeai.verify.support-check=off}）：它本身会多叫一次模型，
 * 而这里的脚本是"按次序念台词"的，多一次调用会把台词错位（手写版用例同样传 {@code TestCheckers.NONE}）。
 * ③ 层自己的用例在 {@code SupportCheckerTest}。
 *
 * <p><b>为什么用假模型测机制</b>：这些是确定性逻辑，用真模型测就变成"网络好的时候能测、每次花钱、
 * 还不可复现"。真实模型的效果数字在 {@code MultiHopLiveTest} 里单独跑、单独报。
 */
@SpringBootTest(properties = "readcodeai.verify.support-check=off")
@Import(StubModelConfig.class)
class SpringAiLoopBehaviorTest {

    @Autowired
    private SpringAiAgentLoop engine;

    @Autowired
    private SymbolQueryService queries;

    @Autowired
    private SymbolQueryRepository repository;

    @Autowired
    private ChainQuestionGenerator chainGenerator;

    @Autowired
    private ProjectIndexer indexer;

    @Autowired
    private ScriptedChatModel model;

    /** 桩在 Spring 上下文里是单例：每条用例前重置，否则台词与调用计数会跨用例累计。 */
    @BeforeEach
    void resetStub() {
        model.reset();
    }

    @Test
    void 走完调用链并给出通过核验的证据() {
        RepoView repo = corpus();
        SymbolView target = firstWithCallers(repo.id());
        List<String> plan = chainGenerator.bfsPlan(target.id(), 2);
        assumeTrue(plan.size() >= 2, "语料里没有两跳以上的调用链，跳过");

        // 理想模型的追问顺序：先从问题里的符号起步，再按 BFS 顺序逐跳上溯
        model.scriptLines(concat(
                new ChatResponse[]{call(target.qualifiedName())},
                plan.stream().map(this::call).toArray(ChatResponse[]::new),
                new ChatResponse[]{ScriptedChatModel.text(
                        finalJson(target.filePath(), target.startLine(), target.endLine()))}));

        AgentAnswer answer = engine.run(repo.id(), root(repo),
                "有哪些方法最终会调用 " + target.qualifiedName() + "？", seedsOf(target), budget(plan.size() + 3));

        assertThat(answer.refused()).as("不该拒答：" + answer.reason()).isFalse();
        assertThat(answer.stopReason()).isEqualTo(StopReason.FINAL);
        assertThat(answer.steps()).hasSize(plan.size() + 1);
        assertThat(answer.subjectsFound())
                .as("走完静态算出的链路后，上游符号集合应当覆盖真值（这是计量口径的自检）")
                .containsAll(chainGenerator.transitiveCallers(target.id(), 2));
        assertThat(answer.verification().verified()).as("结论那一条证据要真的过核验").isEqualTo(1);
        assertThat(answer.repeatedCalls()).isZero();
    }

    @Test
    void 第二次相同调用被拦下并把原因告诉模型() {
        RepoView repo = corpus();
        SymbolView target = firstWithCallers(repo.id());
        ChatResponse call = call(target.qualifiedName());

        model.scriptLines(call, call,
                ScriptedChatModel.text(finalJson(target.filePath(), target.startLine(), target.endLine())));

        AgentAnswer answer = engine.run(repo.id(), root(repo), "谁调用了它", seedsOf(target), budget(5));

        List<AgentStep> steps = answer.steps();
        assertThat(steps).as("两次工具轮（一次执行、一次被拦下）都进轨迹").hasSize(2);
        assertThat(steps.get(1).repeated()).as("第二次相同调用必须被环检测拦下").isTrue();
        assertThat(steps.get(1).observation()).contains("已经做过");
        assertThat(answer.toolCalls()).as("真正执行的查询只有一次").isEqualTo(1);
        assertThat(answer.repeatedCalls()).isEqualTo(1);
        assertThat(answer.refused()).as("被拦一次之后仍能继续干活、最终给出结论").isFalse();
    }

    @Test
    void 连续重复时判为绕圈并主动终止() {
        RepoView repo = corpus();
        SymbolView target = firstWithCallers(repo.id());
        ChatResponse call = call(target.qualifiedName());

        model.scriptLines(call, call, call, call);

        AgentAnswer answer = engine.run(repo.id(), root(repo), "谁调用了它", AgentSeeds.none(), budget(10));

        assertThat(answer.refused()).isTrue();
        assertThat(answer.stopReason()).isEqualTo(StopReason.NO_PROGRESS);
        assertThat(answer.reason()).contains("绕圈");
        assertThat(answer.rounds())
                .as("第 1 轮真查、后 3 轮连续重复即停，不会把 10 轮预算烧完").isEqualTo(4);
    }

    @Test
    void 轮次预算耗尽时停下并交出已查到的轨迹() {
        RepoView repo = corpus();
        SymbolView target = firstWithCallers(repo.id());
        List<String> plan = chainGenerator.bfsPlan(target.id(), 3);
        assumeTrue(plan.size() >= 4, "语料里没有足够长的调用链，跳过");

        model.scriptLines(concat(
                new ChatResponse[]{call(target.qualifiedName())},
                plan.stream().map(this::call).toArray(ChatResponse[]::new)));

        // 轮次上限 5：前 3 轮真查，第 4 轮**留给结论**（模型却还在要求查工具）→ 直接停机
        AgentAnswer answer = engine.run(repo.id(), root(repo), "完整调用链", AgentSeeds.none(), budget(5));

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
    void token预算耗尽时停下() {
        RepoView repo = corpus();
        SymbolView target = firstWithCallers(repo.id());
        List<String> plan = chainGenerator.bfsPlan(target.id(), 3);
        assumeTrue(plan.size() >= 4, "语料里没有足够长的调用链，跳过");

        model.withTokenUsage(1_000, 0)
                .scriptLines(plan.stream().map(this::call).toArray(ChatResponse[]::new));
        BudgetGuard budget = new BudgetGuard(10, 60_000, 2_500, 100, 0, 0);

        AgentAnswer answer = engine.run(repo.id(), root(repo), "完整调用链", AgentSeeds.none(), budget);

        assertThat(answer.stopReason()).as("要能说出停在哪一维").isEqualTo(StopReason.BUDGET_TOKENS);
        assertThat(answer.rounds()).as("每次 1000 token、上限 2500 → 第 3 次之后触顶").isEqualTo(3);
    }

    @Test
    void 证据对不上磁盘时退回重发一次后拒答() {
        RepoView repo = corpus();

        model.scriptLines(
                ScriptedChatModel.text(finalJson("src/does/not/Exist.java", 1, 2)),
                ScriptedChatModel.text(finalJson("src/does/not/Exist.java", 1, 2)));

        AgentAnswer answer = engine.run(repo.id(), root(repo), "随便问问", AgentSeeds.none(), budget(5));

        assertThat(answer.refused()).isTrue();
        assertThat(answer.stopReason()).isEqualTo(StopReason.EVIDENCE_REJECTED);
        assertThat(answer.reason()).contains("核验");
        assertThat(answer.rounds()).as("退回重发一次（共两轮），而不是无限重试").isEqualTo(2);
    }

    @Test
    void 引用没给过的位置时拒答() {
        // ①②层只能证明"这几行真的存在"，证明不了"模型是从给它的材料里引的"。
        // 实测撞到过：模型把结论挂在整类的声明行上（文件行号都有效），而那行从没出现在轨迹里。
        RepoView repo = corpus();
        SymbolView target = firstWithCallers(repo.id());

        // 引一个真实存在、但**轨迹里没给过**的位置：目标类所在文件的第 1 行
        model.scriptLines(call(target.qualifiedName()),
                ScriptedChatModel.text(finalJson(target.filePath(), 1, 1)),
                ScriptedChatModel.text(finalJson(target.filePath(), 1, 1)));

        AgentAnswer answer = engine.run(repo.id(), root(repo), "谁调用了它", AgentSeeds.none(), budget(6));

        assertThat(answer.refused()).as("无依据的引用不许出现在结论里").isTrue();
        assertThat(answer.stopReason()).isEqualTo(StopReason.EVIDENCE_REJECTED);
        assertThat(answer.reason()).contains("核验");
    }

    @Test
    void 模型胡言乱语时不崩并明确归类为格式错误() {
        RepoView repo = corpus();

        model.scriptLines(
                ScriptedChatModel.text("抱歉，我不能这么做。"),
                ScriptedChatModel.text("总之就是不能。" + System.lineSeparator() + "真的不能。"));

        AgentAnswer answer = engine.run(repo.id(), root(repo), "随便问问", AgentSeeds.none(), budget(5));

        assertThat(answer.refused()).isTrue();
        assertThat(answer.stopReason()).isEqualTo(StopReason.FORMAT_ERROR);
        assertThat(answer.rounds()).as("重发一次提示后仍不合法就停").isEqualTo(2);
        assertThat(answer.evidence()).isEmpty();
    }

    @Test
    void 模型给结论但不给证据时拒答() {
        RepoView repo = corpus();

        model.scriptLines(ScriptedChatModel.text(
                "{\"final\":{\"answer\":\"我觉得是这样\",\"evidence\":[],\"refused\":false}}"));

        AgentAnswer answer = engine.run(repo.id(), root(repo), "随便问问", AgentSeeds.none(), budget(5));

        assertThat(answer.refused()).isTrue();
        assertThat(answer.stopReason()).isEqualTo(StopReason.NO_EVIDENCE);
        assertThat(answer.reason()).contains("证据");
    }

    // ---- helpers ----

    /** 一条"查谁调用了它"的工具调用（与手写版用例同一个查法）。 */
    private ChatResponse call(String qualifiedName) {
        return ScriptedChatModel.toolCall("findCallers", "{\"symbol\":\"" + qualifiedName + "\"}");
    }

    /** 一条合法的结论 JSON：**不写 snippet**（只核验文件与行号，与手写版用例同一个口径）。 */
    private static String finalJson(String file, int startLine, int endLine) {
        return """
                {"final":{"answer":"由 %s 第 %d-%d 行给出。","evidence":[{"file":"%s","startLine":%d,"endLine":%d,\
                "snippet":"","why":"工具查出来的位置"}],"refused":false,"refusalReason":""}}"""
                .formatted(file, startLine, endLine, file, startLine, endLine);
    }

    private static BudgetGuard budget(int rounds) {
        return new BudgetGuard(rounds, 60_000, 1_000_000, 100, 0, 0);
    }

    private static Path root(RepoView repo) {
        return Path.of(repo.rootPath());
    }

    /** 与生产路径一致：种子把目标符号的定义位置一并交出去，所以引用它算"有依据"。 */
    private static AgentSeeds seedsOf(SymbolView target) {
        return AgentSeeds.of(
                List.of("[第 0 跳 · 系统] 确定性路由已把问题里的符号解析出来：" + target.qualifiedName()),
                List.of(new AskEvidence(target.filePath(), target.startLine(), target.endLine(), "",
                        "确定性路由解析出的符号")));
    }

    /** 把几段台词接成一串（起步一跳 / 计划里的逐跳 / 结论，各写成一段更好读）。 */
    private static ChatResponse[] concat(ChatResponse[]... parts) {
        List<ChatResponse> all = new ArrayList<>();
        for (ChatResponse[] part : parts) {
            java.util.Collections.addAll(all, part);
        }
        return all.toArray(ChatResponse[]::new);
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
