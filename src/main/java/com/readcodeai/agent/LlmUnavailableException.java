package com.readcodeai.agent;

/**
 * LLM 未配置时抛出。映射成 503 并**说明静态分析能力不受影响** ——
 * 可降级设计要让调用方一眼看出「少了哪块、还剩哪块」，而不是笼统报个错。
 */
public class LlmUnavailableException extends RuntimeException {

    public LlmUnavailableException(String message) {
        super(message);
    }
}
