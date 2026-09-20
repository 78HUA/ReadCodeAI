package com.readcodeai.agent;

import com.readcodeai.agent.model.AgentAnswer;
import com.readcodeai.agent.model.AgentStep;
import com.readcodeai.config.BudgetGuard;
import com.readcodeai.evidence.EvidenceVerifier;
import com.readcodeai.index.ProjectIndexer;
import com.readcodeai.index.store.SymbolQueryRepository;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.RepoView;
import com.readcodeai.retrieve.model.SymbolView;
import com.readcodeai.verify.TestCheckers;
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
 * 提示词瘦身的验收：**早轮次留下事实、丢掉代码；最近几跳仍然全量**。
 *
 * <p>为什么值得单独钉：压缩是有代价的操作 —— 压多了模型失去推理依据（下一步该往哪跳），
 * 压少了等于没省。所以这里同时断言两边：
 * <ul>
 *   <li>早轮次的**原始输出**（那坨代码文本）确实不在提示词里了；</li>
 *   <li>早轮次**查到的名字**（subjects）与证据位置仍然在 —— 模型靠它们决定下一步；</li>
 *   <li>最近 {@code keepFullObservations} 跳仍然是原文（给结论前要看得到刚查到的代码）；</li>
 *   <li>{@code keep=0} 时与旧行为一致（不压缩是逃生门，不能悄悄变味）。</li>
 * </ul>
 * 用脚本模型跑：不起网络、不花 token，断言可以精确到"提示词里有没有这一段"。
 */
@SpringBootTest
class PromptCompactionTest {

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private EvidenceVerifier evidenceVerifier;

    @Autowired
    private SymbolQueryService queries;

    @Autowired
    private SymbolQueryRepository repository;

    @Autowired
    private ProjectIndexer indexer;

    @Test
    void olderHopsKeepTheirFactsButLoseTheirCode() {
        RepoView repo = corpus();
        // 5 跳：keep=2 时，末轮的提示词里第 1~3 跳被压、第 4~5 跳保留全文 —— 能同时验到两边
        List<SymbolView> targets = withCallers(repo.id(), 5);
        assumeTrue(targets.size() >= 5, "语料里带调用者的方法不够 5 个，跳过");
        // 结论引的证据要落在轨迹里：用第一跳查到的调用点（与真实模型那次实验同一套规矩）
        var caller = queries.callers(targets.get(0).id()).stream()
                .filter(call -> call.symbolId() != null)
                .findFirst()
                .orElseThrow();

        Run compacted = run(repo, targets, caller.callSiteFile(), caller.callLine(), 2);
        Run full = run(repo, targets, caller.callSiteFile(), caller.callLine(), 0);

        List<AgentStep> steps = compacted.answer().steps();
        assertThat(steps).as("脚本要真的走出 5 跳").hasSize(5);
        String lastCompact = last(compacted.client().prompts());
        String lastFull = last(full.client().prompts());

        // 1) 早轮次的原始输出：旧行为里有、瘦身后没有
        for (int hop = 0; hop < 3; hop++) {
            assertThat(lastFull).contains(steps.get(hop).observation());
            assertThat(lastCompact).as("第 %d 跳的代码文本应当被压掉", hop + 1)
                    .doesNotContain(steps.get(hop).observation());
        }

        // 2) 但事实必须留着：查到了哪些符号（压缩有上限，所以只断言"保留范围内的"都在）
        assertThat(steps.get(0).subjects()).as("第一跳得有 subjects 才谈得上'保留事实'").isNotEmpty();
        for (String subject : steps.get(0).subjects().stream().limit(AgentLoop.COMPACT_KEEP_SUBJECTS).toList()) {
            assertThat(lastCompact).as("第一跳查到的符号 %s 必须留在提示词里（模型靠它决定下一步往哪跳）", subject)
                    .contains(subject);
        }
        assertThat(lastCompact).as("超出上限的部分不列出来，但**条数**要说清，模型才知道还有更多")
                .contains(steps.get(0).subjects().size() + " 个结果");
        assertThat(steps.get(0).evidence()).as("这条用例要能验到'证据位置被保留'").isNotEmpty();
        for (var evidence : steps.get(0).evidence().stream().limit(AgentLoop.COMPACT_KEEP_LOCATIONS).toList()) {
            assertThat(lastCompact).as("证据位置 %s 也要留着（模型可以据此直接引用）", evidence.location())
                    .contains(evidence.location());
        }

        // 3) 最近 2 跳仍然全量（keep=2 时第 1~3 跳被压、第 4~5 跳不压）
        assertThat(lastCompact).contains(steps.get(3).observation());
        assertThat(lastCompact).contains(steps.get(4).observation());

        // 4) 压缩标记与体积
        assertThat(lastCompact).as("压缩要说明白，模型才会知道'需要细节可以重查'").contains("原始输出已略去");
        assertThat(lastFull).doesNotContain("原始输出已略去");
        assertThat(lastCompact.length()).as("瘦身后的提示词必须真的更短").isLessThan(lastFull.length());

        // 5) **定量的节省**：同一条脚本轨迹下逐轮比字符数 —— 这是不受"模型这条轨迹走哪"干扰的干净数字，
        //    真实模型 A/B 里两条轨迹会分叉，只有脚本模型能给出因果干净的对比
        System.out.println(System.lineSeparator() + "=== 提示词瘦身（同一脚本轨迹 · 逐轮对比）===");
        int compactedTotal = 0;
        int fullTotal = 0;
        for (int i = 0; i < compacted.client().prompts().size(); i++) {
            int compactChars = compacted.client().prompts().get(i).length();
            int fullChars = full.client().prompts().get(i).length();
            compactedTotal += compactChars;
            fullTotal += fullChars;
            System.out.printf("第 %d 轮：%d 字符 → %d 字符（省 %d）%n", i + 1, fullChars, compactChars,
                    fullChars - compactChars);
        }
        System.out.printf("%d 轮合计：%d 字符 → %d 字符（省 %.0f%%）· 按脚本模型的估算口径约 %d token → %d token%n",
                compacted.client().prompts().size(), fullTotal, compactedTotal,
                100.0 * (fullTotal - compactedTotal) / fullTotal, fullTotal / 3, compactedTotal / 3);
    }

    @Test
    void keepingEverythingIsStillAnOptionForEscaping() {
        RepoView repo = corpus();
        List<SymbolView> targets = withCallers(repo.id(), 1);
        assumeTrue(!targets.isEmpty(), "语料里没有带调用者的方法，跳过");
        var caller = queries.callers(targets.get(0).id()).stream()
                .filter(call -> call.symbolId() != null)
                .findFirst()
                .orElseThrow();

        // keep=0：连"最近 K 跳"也不压 —— 老行为原样保留，作为 A/B 对照与逃生门
        Run run = run(repo, targets, caller.callSiteFile(), caller.callLine(), 0);
        String prompt = last(run.client().prompts());
        assertThat(run.answer().refused()).as("不压缩也要能正常给出结论").isFalse();
        assertThat(prompt).contains(run.answer().steps().get(0).observation());
    }

    // ---- helpers ----

    private record Run(AgentAnswer answer, ScriptedLlmClient client) {
    }

    private Run run(RepoView repo, List<SymbolView> targets, String evidenceFile, int evidenceLine, int keep) {
        List<String> script = new ArrayList<>();
        for (SymbolView target : targets) {
            script.add(ScriptedLlmClient.callTool("findCallers", "symbol", target.qualifiedName()));
        }
        script.add(ScriptedLlmClient.answer("上游链路已查完", evidenceFile, evidenceLine, evidenceLine, null));
        ScriptedLlmClient client = ScriptedLlmClient.lines(script.toArray(String[]::new));
        AgentLoop loop = new AgentLoop(toolRegistry, evidenceVerifier, TestCheckers.NONE, client, keep);

        AgentAnswer answer = loop.run(repo.id(), Path.of(repo.rootPath()),
                "谁调用了这几个方法？间接的也要。", AgentLoop.Seeds.none(),
                new BudgetGuard(8, 60_000, 1_000_000, 100, 0, 0));
        return new Run(answer, client);
    }

    private static String last(List<String> prompts) {
        return prompts.get(prompts.size() - 1);
    }

    private List<SymbolView> withCallers(long repoId, int count) {
        return repository.mostCalledMethods(repoId, 30).stream()
                .filter(symbol -> !queries.callers(symbol.id()).isEmpty())
                .limit(count)
                .toList();
    }

    private RepoView corpus() {
        var repo = TestCorpus.resolve(indexer, queries);
        assumeTrue(repo.isPresent(), "语料不存在（sample-repos 或 -Dreadcodeai.verify.repo=），跳过");
        assumeTrue(!"UNAVAILABLE".equals(repo.get().status()), "语料索引不可用，跳过");
        return repo.get();
    }
}
