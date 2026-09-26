package com.readcodeai.agent.springai;

import com.readcodeai.agent.AgentService;
import com.readcodeai.agent.model.AgentAnswer;
import com.readcodeai.agent.model.AgentMode;
import com.readcodeai.agent.model.AskEvidence;
import com.readcodeai.config.LlmClient;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.verify.LiveLlm;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **本次最小迁移的关键验证**：Spring AI 引擎在真仓库上跑一条链式问题，看四件事成不成立。
 *
 * <ol>
 *   <li>框架真的会跑多跳工具循环（轨迹非空、至少两跳）；</li>
 *   <li>结论带证据；</li>
 *   <li><b>结构化证据留得住</b> —— 框架只把工具的字符串回灌给模型，证据是在
 *       {@link SpringAiLoopState} 的工具侧收集的，这条链路必须真的能走通；</li>
 *   <li>四维预算的记账在框架循环里也没丢（轮次/token 都记上了）。</li>
 * </ol>
 *
 * <p>门禁与项目里其它 live 测试一致：没配 Key 或接口不通 → 跳过（外部波动不该变成红色）。
 */
@SpringBootTest(properties = {
        "readcodeai.agent.engine=spring-ai",
        "readcodeai.llm.keep-full-observations=2"
})
class SpringAiEngineLiveTest {

    private static final Logger log = LoggerFactory.getLogger(SpringAiEngineLiveTest.class);

    private static final String QUESTION = "AgentService.ask 的 question 参数是从哪里传进来的？";

    @Autowired
    private AgentService agentService;

    @Autowired
    private SymbolQueryService symbolQueryService;

    @Autowired
    private LlmClient llmClient;

    @Test
    void 链式问题在SpringAI引擎上能给出带证据的结论() {
        LiveLlm.assumeReachable(llmClient);
        long repoId = symbolQueryService.requireLatestRepoId();

        AgentAnswer answer = agentService.ask(repoId, QUESTION, AgentMode.MULTI_HOP, null, null, false);

        log.info("[SpringAI 验证] 结论：{}", answer.answer());
        log.info("[SpringAI 验证] 证据：{}", answer.evidence().stream().map(AskEvidence::location).toList());
        log.info("[SpringAI 验证] 轨迹：{}", answer.trailDigest());
        log.info("[SpringAI 验证] 轮次 {} · 跳数 {} · token in/out {}/{} · 耗时 {} ms · stop={} · 拒答={}",
                answer.rounds(), answer.toolCalls(), answer.promptTokens(), answer.completionTokens(),
                answer.latencyMs(), answer.stopReason(), answer.refused());
        if (answer.refused()) {
            log.info("[SpringAI 验证] 拒答原因：{}", answer.reason());
        }

        assertThat(answer.steps()).as("框架真的跑了工具循环（轨迹非空）").isNotEmpty();
        assertThat(answer.toolCalls()).as("至少两跳才叫多跳").isGreaterThanOrEqualTo(2);
        assertThat(answer.promptTokens()).as("预算记账在框架循环里也没丢").isGreaterThan(0);
        assertThat(answer.refused()).as("这条问题在仓库里有答案，不该拒答：" + answer.reason()).isFalse();
        assertThat(answer.answer()).as("要有结论").isNotBlank();
        assertThat(answer.evidence()).as("**证据留住了**（工具侧收集 + 磁盘核验通过）").isNotEmpty();
    }
}
