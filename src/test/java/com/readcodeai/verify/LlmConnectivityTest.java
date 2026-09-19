package com.readcodeai.verify;

import com.readcodeai.config.LlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 0 步验证 3 的另一半：**配好 Key 时的真实链路**（此前只验证了「无 Key 降级」）。
 *
 * <p>没配 Key 时自动跳过 —— 这样它既能在有 Key 的机器上产出实测数字，
 * 又不会让没配 Key 的环境（或别人的克隆）因为跑不了而失败。
 *
 * <p>运行前先 {@code source notes/llm-env.sh}（该文件在 .gitignore 里）。
 */
@SpringBootTest
class LlmConnectivityTest {

    @Autowired
    private LlmClient llmClient;

    @Test
    void callsTheConfiguredModelAndReportsLatencyAndTokens() {
        // 连不上就跳过并写明理由（外部波动不是代码问题，见 LiveLlm）
        LiveLlm.assumeReachable(llmClient);

        long start = System.nanoTime();
        LlmClient.Completion completion = llmClient.complete(
                "You are a connectivity probe for an automated test. Follow the instruction literally.",
                "Reply with exactly two words: hello world");
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        System.out.printf("%n[LLM 连通性] model=%s · 延迟=%d ms · prompt_tokens=%d · completion_tokens=%d%n"
                        + "            回复：%s%n",
                llmClient.model(), elapsedMillis,
                completion.promptTokens(), completion.completionTokens(), completion.content());

        assertThat(completion.content()).as("模型必须返回非空内容").isNotBlank();
        assertThat(completion.totalTokens()).as("用量必须被解析出来（成本统计依赖它）").isGreaterThan(0);
    }
}
