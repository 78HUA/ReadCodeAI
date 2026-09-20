package com.readcodeai.api;

/**
 * 请求被限流。
 *
 * <p>它走 {@link ApiExceptionHandler} 统一成 {@code {code:429, message}} 的形状 ——
 * 与项目里其它错误同一套外形，客户端不用为限流单独写一套解析。HTTP 状态码同样是 429。
 */
public class RateLimitedException extends RuntimeException {

    private final long retryAfterSeconds;
    private final int remaining;

    public RateLimitedException(String message, long retryAfterSeconds, int remaining) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
        this.remaining = remaining;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public int remaining() {
        return remaining;
    }
}
