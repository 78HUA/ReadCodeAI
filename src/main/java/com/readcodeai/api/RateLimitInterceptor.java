package com.readcodeai.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 给"会调模型"的接口装闸门（令牌桶，跨实例）。
 *
 * <h3>为什么只拦这几个接口</h3>
 * 限流的目的是保护**稀缺资源**（模型额度/时间）。索引提交已经由队列容量兜着（满了明确报忙），
 * 而符号查询、全文检索这些是本地毫秒级操作，限它们只会让正常的界面操作变得莫名卡顿 ——
 * 把闸门装在不缺资源的地方，是"为了显得专业"的典型做法，这里不做。
 *
 * <h3>按什么维度限</h3>
 * 优先取网关传进来的 {@code X-Forwarded-For}（多用户部署时这才是真实来源），
 * 否则用连接地址。项目没有登录体系，所以"按用户"这一步留给接入认证之后 ——
 * 现在的实现是"按来源"，够用且诚实。
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final RateLimiter limiter;

    public RateLimitInterceptor(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        RateLimiter.Decision decision = limiter.tryAcquire(clientKey(request));

        // 无论过没过都回带额度信息：客户端能自己退让，而不是等被拒了才知道
        response.setHeader("X-RateLimit-Limit", String.valueOf(Math.max(0, decision.remaining())));
        if (decision.allowed()) {
            return true;
        }
        response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
        log.info("限流拦下一次请求：{} {}（{}）", request.getMethod(), request.getRequestURI(),
                clientKey(request));
        throw new RateLimitedException("请求过于频繁（会调用模型的接口有令牌桶限流）：请等 "
                + decision.retryAfterSeconds() + " 秒后重试", decision.retryAfterSeconds(), decision.remaining());
    }

    /** 客户端标识：优先 X-Forwarded-For 的第一段，否则连接地址。 */
    static String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return "ip:" + (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String remote = request.getRemoteAddr();
        return "ip:" + (remote == null ? "unknown" : remote);
    }
}
