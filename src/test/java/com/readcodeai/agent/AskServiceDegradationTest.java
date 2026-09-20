package com.readcodeai.agent;

import com.readcodeai.agent.model.AskAnswer;
import com.readcodeai.config.LlmClient;
import com.readcodeai.config.ReadCodeAiProperties;
import com.readcodeai.evidence.EvidenceRepair;
import com.readcodeai.evidence.EvidenceVerifier;
import com.readcodeai.index.ProjectIndexer;
import com.readcodeai.index.store.SymbolQueryRepository;
import com.readcodeai.retrieve.ContextSelector;
import com.readcodeai.retrieve.QueryRouter;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.TextRetriever;
import com.readcodeai.retrieve.model.SymbolView;
import com.readcodeai.verify.TestAnswerLogs;
import com.readcodeai.verify.TestCheckers;
import com.readcodeai.verify.TestCorpus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 问答接口的降级行为：**没配 LLM 时不是「静默返回空答案」，而是明确报错并说明还剩什么能力**。
 *
 * <p>与 {@code LlmDegradationTest} 的分工：那条测的是客户端装配，这条测的是**接口层的行为**。
 */
@SpringBootTest(properties = {
        "readcodeai.llm.enabled=true",
        "readcodeai.llm.api-key=",
        "readcodeai.llm.base-url=",
        "readcodeai.llm.model="
})
class AskServiceDegradationTest {

    @Autowired
    private AnswerService answerService;

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
    private SymbolQueryRepository repository;

    @Autowired
    private ProjectIndexer indexer;

    @Autowired
    private ReadCodeAiProperties properties;

    @Test
    void refusesToAnswerWhenNoLlmIsConfiguredAndSaysWhatStillWorks() {
        assertThatThrownBy(() -> answerService.ask(null, "登录检查在哪做的", null, 5))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("语义问答不可用")
                .hasMessageContaining("确定性能力不受影响");
    }

    /**
     * 模型接口挂了（超时、5xx）时要说清是**调用故障**，不能表现成"仓库里没有答案"。
     *
     * <p>实测撞到过：一轮测试里连着调用很多次之后，接口挂在 60 秒读超时上。
     * 多跳那条路一直有这道兜底（`StopReason.LLM_CALL_FAILED`），单跳当时把异常抛成了 500 ——
     * 同一个故障两种表现，使用者看到的解释完全不同。这条用例把两者钉成一致。
     */
    @Test
    void aModelCallFailureIsReportedAsAFailureNotAsAnEmptyRepository() {
        var corpus = TestCorpus.resolve(indexer, queries);
        assumeTrue(corpus.isPresent(), "语料不存在（sample-repos 或 -Dreadcodeai.verify.repo=），跳过");
        long repoId = corpus.get().id();
        List<SymbolView> methods = repository.mostCalledMethods(repoId, 20);
        assumeTrue(!methods.isEmpty(), "语料里没有可用的方法符号，跳过");

        LlmClient broken = new LlmClient() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String model() {
                return "broken";
            }

            @Override
            public Completion complete(String systemPrompt, String userPrompt) {
                throw new IllegalStateException("Read timed out");
            }
        };
        AnswerService service = new AnswerService(textRetriever, queries, queryRouter, contextSelector,
                evidenceVerifier, evidenceRepair, TestCheckers.NONE, broken, properties,
                TestAnswerLogs.silent(properties));

        // 题面里带真实标识符：纯中文问句在英文语料上会先因"检索为空"拒答，那就测不到模型这一步了
        AskAnswer answer = service.ask(repoId,
                "这个方法大致是做什么的：" + methods.get(0).qualifiedName() + "？", null, 8);

        assertThat(answer.refused()).isTrue();
        assertThat(answer.refusalReason()).contains("调用故障").contains("Read timed out");
    }
}
