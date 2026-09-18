package com.readcodeai.agent;

/**
 * 模型输出不是合法 JSON。
 *
 * <p><b>它是可计数的质量指标，不是崩溃</b>：小模型偶发格式错误是常态，
 * 上层据此拒答或重发一次提示，并把它记进日志 —— 而不是让整次请求 500。
 */
public class ModelOutputFormatException extends RuntimeException {

    private final transient String rawOutput;

    public ModelOutputFormatException(String message, String rawOutput) {
        super(message);
        this.rawOutput = rawOutput;
    }

    public String rawOutput() {
        return rawOutput;
    }
}
