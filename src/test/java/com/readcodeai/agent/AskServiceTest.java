package com.readcodeai.agent;

import com.readcodeai.agent.model.AnsweredBy;
import com.readcodeai.agent.model.AskAnswer;
import com.readcodeai.agent.model.AskEvidence;
import com.readcodeai.config.LlmClient;
import com.readcodeai.index.ProjectIndexer;
import com.readcodeai.index.store.SymbolQueryRepository;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.RepoView;
import com.readcodeai.retrieve.model.SymbolView;
import com.readcodeai.verify.TestCorpus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 第 2 步验证（后半）：问答接口。
 *
 * <p>要验证的不只是「答得像不像」，而是两条**结构约束**：
 * ① 答案必须带证据；② **模型只能引用给它的片段** —— 它引用的文件必须在本次检索结果里。
 * 后者是防幻觉的第一道闸门，成本几乎为零（不用读文件，比对集合即可）。
 *
 * <p>语料由 {@code -Dreadcodeai.verify.repo} 指定并**显式锁定 repoId**；
 * 没配 Key 时自动跳过。运行前先 {@code source notes/llm-env.sh}。
 */
@SpringBootTest
class AskServiceTest {

    @Autowired
    private AnswerService answerService;

    @Autowired
    private LlmClient llmClient;

    @Autowired
    private ProjectIndexer indexer;

    @Autowired
    private SymbolQueryService queryService;

    @Autowired
    private SymbolQueryRepository repository;

    @Test
    void answersWithEvidenceAndOnlyCitesChunksItWasGiven() {
        assumeTrue(llmClient.available(), "未配置 LLM，跳过问答验证");

        RepoView repo = corpus();
        SymbolView target = mostCalled(repo.id());
        String question = "这个类大致是做什么的：" + target.qualifiedName() + "？";
        AskAnswer answer = answerService.ask(repo.id(), question, null, 8);

        System.out.printf("%n[问答] 问题：%s%n", question);
        System.out.printf("  结论：%s%n", answer.refused() ? "(拒答) " + answer.refusalReason() : answer.answer());
        System.out.printf("  证据：%n");
        answer.evidence().forEach(e -> System.out.printf("    %s  —— %s%n", e.location(), e.why()));
        System.out.printf("  检索到 %d 段 · 模型 %s · prompt=%d completion=%d · %d ms%n",
                answer.chunksUsed(), llmClient.model(), answer.promptTokens(),
                answer.completionTokens(), answer.latencyMs());
        System.out.printf("  证据校验：通过 %d 条 · 拦下 %d 条 · 定向修正 %d 处%n",
                answer.verification().verified(), answer.verification().mismatch(),
                answer.verification().repairs().size());

        // 第 4 步的分水岭：**返回给使用者的每一条证据都必须真的通过过磁盘核验**。
        // 注意这里断言的是「不变式」而不是「一定答出来了」——
        // 模型会随机地把证据引到错误的文件/行号上，那时**拒答才是正确行为**。
        // 真正要守住的是：**只要返回了答案，里面就不许有未通过核验的证据。**
        if (answer.refused()) {
            assertThat(answer.refusalReason())
                    .as("拒答的理由必须说清是「证据没过核验」还是「片段不足以回答」")
                    .satisfiesAnyOf(
                            reason -> assertThat(reason).contains("核验"),
                            reason -> assertThat(reason).contains("不足以回答"),
                            reason -> assertThat(reason).contains("合法 JSON"));
            System.out.println("  本次拒答（模型给的证据没过核验或片段不足），也是正确行为");
            return;
        }

        assertThat(answer.answeredBy()).isEqualTo(AnsweredBy.LLM);
        assertThat(answer.answer()).as("必须给出结论").isNotBlank();
        assertThat(answer.evidence()).as("没有证据的答案不许返回").isNotEmpty();
        assertThat(answer.verification().verified())
                .as("通过核验的证据数应等于返回的证据数")
                .isEqualTo(answer.evidence().size());
        // 这里断言的**不变量**是"返回的证据都通过过核验"（上面那条已覆盖）。
        // 不再断言 verification().firstPassClean()：它说的是"首轮一条都没被拦下"，
        // 而首轮被拦、定向修正后通过的证据是**正确流程**（实测：模型引错行号后被修正）。
        // 把两者混为一谈会把正常行为判成失败 —— 这个误判是第 5 步跑真实模型时暴露的。
        System.out.printf("  首轮未通过 %d 条 · 定向修正 %d 处（修正后仍须重新核验）%n",
                answer.verification().mismatch(), answer.verification().repairs().size());

        // 防幻觉的第一道闸门：模型引用的文件必须来自本次检索结果，不能凭空出现
        for (AskEvidence evidence : answer.evidence()) {
            assertThat(answer.retrievedFrom())
                    .as("证据 %s 不在本次检索到的片段里（模型编造了文件？）", evidence.location())
                    .anySatisfy(location -> assertThat(location).startsWith(evidence.file() + ":"));
            assertThat(evidence.startLine()).as("行号必须为正").isPositive();
            assertThat(evidence.endLine()).isGreaterThanOrEqualTo(evidence.startLine());
        }
    }

    @Test
    void scopingToOneFileNarrowsTheSearch() {
        assumeTrue(llmClient.available(), "未配置 LLM，跳过问答验证");

        RepoView repo = corpus();
        SymbolView target = mostCalled(repo.id());
        String directory = target.filePath().substring(0, target.filePath().lastIndexOf('/') + 1);
        AskAnswer answer = answerService.ask(repo.id(),
                target.name() + " 这些代码是做什么的？", directory, 8);

        System.out.printf("%n[单文件范围问答] 范围 %s · 检索到的片段：%s%n", directory, answer.retrievedFrom());

        assertThat(answer.retrievedFrom()).isNotEmpty();
        assertThat(answer.retrievedFrom())
                .as("限定范围后，检索结果必须都落在该目录下")
                .allSatisfy(location -> assertThat(location).contains(directory));
    }

    /** 锁定本次要测的语料（不能依赖「最近索引的仓库」）。 */
    private RepoView corpus() {
        var corpus = TestCorpus.resolve(indexer, queryService);
        assumeTrue(corpus.isPresent(), "语料 " + TestCorpus.SAMPLE + " 不存在，跳过");
        return corpus.get();
    }

    /** 题目从语料里长出来：写死某个项目的符号名会让整个测试类绑死在一个语料上。 */
    private SymbolView mostCalled(long repoId) {
        return repository.mostCalledMethods(repoId, 1).get(0);
    }
}
