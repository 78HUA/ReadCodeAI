package com.readcodeai.config;

/**
 * LLM 未配置时的降级实现。
 *
 * <p>两条相反的规则要同时成立：**不阻断启动**（缺配置只是少一项能力，不是错误），
 * 但**一旦被真正调用就大声失败** —— 静默返回空字符串会让上层把「模型没说话」和
 * 「模型说这里没有答案」混为一谈，那是这个项目最不能容忍的错误。
 */
public class NoopLlmClient implements LlmClient {

    private final String reason;

    public NoopLlmClient(String reason) {
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String model() {
        return "none";
    }

    @Override
    public Completion complete(String systemPrompt, String userPrompt) {
        throw new IllegalStateException(
                "LLM 不可用（" + reason + "）：请配置 readcodeai.llm.* ，或改走纯静态分析路径");
    }
}
