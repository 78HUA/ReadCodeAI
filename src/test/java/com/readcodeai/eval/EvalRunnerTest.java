package com.readcodeai.eval;

import com.readcodeai.eval.model.GeneratedQuestion;
import com.readcodeai.index.ProjectIndexer;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.RepoView;
import com.readcodeai.verify.TestCorpus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 第 6 步验证：**评估集自动构造 + 自动判卷**（分水岭二，本项目最大的差异化）。
 *
 * <p>三条要证明的东西：
 * <ol>
 *   <li><b>规模</b>：能脚本批量出题（这里 200 道，受限于语料规模；机制本身与题量无关）</li>
 *   <li><b>可复现</b>：同一 seed 出**完全相同**的一批题 —— 否则跨版本的数字不可比</li>
 *   <li><b>标准答案不来自 LLM</b>：全部由符号表 / 调用图 / 类型关系算出</li>
 * </ol>
 *
 * <p>⚠️ 这套评估覆盖的是**确定性管线**（出题→路由→查询→组织→证据），
 * 开放问答的准确率由人工判定的抽查来量，两边分开报 —— 混成一个"准确率"才是骗人。
 */
@SpringBootTest
class EvalRunnerTest {

    private static final long SEED = 20260918L;

    /**
     * 每种题型出多少道。注意**实际总数会少于 5 × PER_TYPE** ——
     * `IMPLEMENTS` 受语料限制：只有"接口 + 至少一个实现类"才能出题，gson 里这类组合很少。
     * 这是语料的性质，不是生成器的问题（换个更大的语料就多）。
     */
    private static final int PER_TYPE = 50;

    @Autowired
    private EvalRunner evalRunner;

    @Autowired
    private QuestionGenerator generator;

    @Autowired
    private ProjectIndexer indexer;

    @Autowired
    private SymbolQueryService queryService;

    @Test
    void generatesHundredsOfQuestionsAndGradesThemAutomatically() {
        RepoView repo = corpus();

        EvalRunner.Report report = evalRunner.run(repo.id(), SEED, PER_TYPE);
        System.out.println(System.lineSeparator() + report.toReport());

        // **诊断必须先打，再断言** —— 否则断言一挂，样例就再也看不到了（踩过这个坑）
        System.out.printf("%n[评估集] 整体命中 %d/%d = %.1f%% · 平均耗时 %.0f ms/题%n",
                report.hit(), report.total(), report.overallHitRate() * 100,
                report.items().stream().mapToLong(EvalRunner.ItemResult::latencyMs).average().orElse(0));

        // 三类都要打：判分不通过的、拒答的、抛异常的（最后一类最要紧）
        System.out.println("未完全通过的样例（最多 10 条）：");
        report.items().stream()
                .filter(item -> item.failed() || item.refused()
                        || (item.grade() != null && !item.grade().hit()))
                .limit(10)
                .forEach(item -> System.out.printf("   [%s] %s%n      真值 %s%n      结果 %s%n",
                        item.question().type(), item.question().questionText(),
                        item.question().truthKeys(),
                        item.failed() || item.refused() ? item.answerExcerpt() : item.grade().note()));

        assertThat(report.total())
                .as("题量随语料规模变化（reggie 这种小语料产不出 200 道）；机制本身与题量无关")
                .isGreaterThanOrEqualTo(50);
        assertThat(report.byType().keySet())
                .as("五种题型都该出到题")
                .containsExactlyInAnyOrderElementsOf(List.of(
                        com.readcodeai.eval.model.QType.values()));
        assertThat(report.failed())
                .as("不该有题目在作答时抛异常")
                .isZero();
        assertThat(report.hit())
                .as("确定性管线的命中率不该是 0（否则说明路由或查询断了）")
                .isGreaterThan(report.total() / 2);
    }

    @Test
    void theSameSeedProducesTheSameQuestionsSoRunsAreComparable() {
        RepoView repo = corpus();

        List<GeneratedQuestion> first = generator.generate(repo.id(), SEED, 5);
        List<GeneratedQuestion> second = generator.generate(repo.id(), SEED, 5);

        assertThat(second).as("同一 seed 必须出同一批题，否则跨版本的数字没有可比性")
                .isEqualTo(first);

        List<GeneratedQuestion> differentSeed = generator.generate(repo.id(), SEED + 1, 5);
        assertThat(differentSeed).as("换个 seed 应该出不同的题（抽样确实在起作用）")
                .isNotEqualTo(first);
    }

    /** 锁定本次要测的语料（不能依赖「最近索引的仓库」）。 */
    private RepoView corpus() {
        var resolved = TestCorpus.resolve(indexer, queryService);
        assumeTrue(resolved.isPresent(), "语料 " + TestCorpus.SAMPLE + " 不存在，跳过");
        return resolved.get();
    }
}
