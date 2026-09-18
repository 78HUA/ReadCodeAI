package com.readcodeai.agent;

import com.readcodeai.agent.model.AnsweredBy;
import com.readcodeai.agent.model.AskAnswer;
import com.readcodeai.agent.model.AskEvidence;
import com.readcodeai.config.LlmClient;
import com.readcodeai.index.ProjectIndexer;
import com.readcodeai.retrieve.SymbolQueryService;
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

    @Test
    void answersWithEvidenceAndOnlyCitesChunksItWasGiven() {
        assumeTrue(llmClient.available(), "未配置 LLM，跳过问答验证");

        AskAnswer answer = answerService.ask(corpusRepoId(), "登录检查是在哪里做的？", null, 8);

        System.out.printf("%n[问答] 问题：登录检查是在哪里做的？%n");
        System.out.printf("  结论：%s%n", answer.refused() ? "(拒答) " + answer.refusalReason() : answer.answer());
        System.out.printf("  证据：%n");
        answer.evidence().forEach(e -> System.out.printf("    %s  —— %s%n", e.location(), e.why()));
        System.out.printf("  检索到 %d 段 · 模型 %s · prompt=%d completion=%d · %d ms%n",
                answer.chunksUsed(), llmClient.model(), answer.promptTokens(),
                answer.completionTokens(), answer.latencyMs());

        assertThat(answer.refused()).as("这个问题在语料里是有答案的，不该拒答").isFalse();
        assertThat(answer.answeredBy())
                .as("这是模糊语义类问题，应该由模型组织语言")
                .isEqualTo(AnsweredBy.LLM);
        assertThat(answer.answer()).as("必须给出结论").isNotBlank();
        assertThat(answer.evidence()).as("没有证据的答案不许返回").isNotEmpty();

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

        AskAnswer answer = answerService.ask(corpusRepoId(), "这个过滤器做了什么？",
                "src/main/java/com/harmony/reggie/filter/", 8);

        System.out.printf("%n[单文件范围问答] 检索到的片段：%s%n", answer.retrievedFrom());

        assertThat(answer.retrievedFrom()).isNotEmpty();
        assertThat(answer.retrievedFrom())
                .as("限定范围后，检索结果必须都落在该目录下")
                .allSatisfy(location -> assertThat(location).contains("reggie/filter/"));
    }

    /** 锁定本次要测的语料（不能依赖「最近索引的仓库」）。 */
    private long corpusRepoId() {
        var corpus = TestCorpus.resolve(indexer, queryService);
        assumeTrue(corpus.isPresent(), "语料 " + TestCorpus.SAMPLE + " 不存在，跳过");
        return corpus.get().id();
    }
}
