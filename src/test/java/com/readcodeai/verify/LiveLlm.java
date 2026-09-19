package com.readcodeai.verify;

import com.readcodeai.config.LlmClient;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 需要真实模型的测试的统一入口：**先确认接口真的能连上，连不上就跳过并写明理由**。
 *
 * <p>为什么要有这一层：模型接口会波动（实测撞到过 {@code open.bigmodel.cn} 直接 I/O error，
 * 以及读超时）。这类失败**不是代码缺陷**，但会把整套测试染红，进而让人开始忽略红色 —— 那比失败本身更危险。
 * 所以统一做法是：不可达 → 跳过 + 把原始错误打出来（跳过≠悄悄放过，理由必须留在输出里）。
 *
 * <p>跳过与"没配 Key"是两回事：没配 Key 是**预期内**的降级；接口不可达是**外部故障**。
 * 两者的消息要能分开看，否则排查时会走错方向。
 */
public final class LiveLlm {

    private LiveLlm() {
    }

    /** 配了 Key 且真的能连上才继续；否则跳过。 */
    public static void assumeReachable(LlmClient client) {
        assumeTrue(client.available(), "未配置 LLM（readcodeai.llm.*），跳过需要模型的验证");
        try {
            client.complete("You are a connectivity probe for an automated test.",
                    "Reply with exactly two words: hello world");
        } catch (RuntimeException e) {
            assumeTrue(false, "模型接口当前不可用（" + e.getClass().getSimpleName() + "："
                    + firstLine(e.getMessage()) + "），跳过 —— 属外部波动，与代码无关");
        }
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "(无消息)";
        }
        int newline = message.indexOf('\n');
        String line = newline < 0 ? message : message.substring(0, newline);
        return line.length() <= 160 ? line : line.substring(0, 160) + "...";
    }
}
