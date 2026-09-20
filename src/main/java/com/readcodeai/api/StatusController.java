package com.readcodeai.api;

import com.readcodeai.agent.cache.AnswerCache;
import com.readcodeai.config.EmbeddingClient;
import com.readcodeai.config.LlmClient;
import com.readcodeai.config.ReadCodeAiProperties;
import com.readcodeai.index.AsyncIndexer;
import com.readcodeai.index.queue.IndexTaskQueue;
import com.readcodeai.index.queue.RepoLock;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;

/**
 * 运行状态：**把"现在是什么配置、哪些能力降级了"一次说清楚**（只读，不提供修改）。
 *
 * <h3>为什么值得有</h3>
 * 这个项目的依赖很多是可降级的（模型 / Redis / broker / 锁 / 限流），而**降级是静默生效的**：
 * 没配 Key 就少了语义问答，没起 Redis 就少了缓存与锁，没起 broker 就走进程内队列。
 * 这些在启动日志里各说一句，但日志是要去翻的 —— 页面上一眼看得到，才谈得上"可运维"。
 *
 * <h3>为什么不让它在页面上改配置</h3>
 * 凭据（API Key / 数据库口令）与启动参数属于**部署者**，不属于访问者：这个服务没有登录体系，
 * 一个能改配置的页面等于把凭据暴露给任何能打开它的人。所以这里是**只读**的 ——
 * 要改就改环境变量再重启（README 的配置表写了每项的含义）。
 */
@RestController
@RequestMapping("/api/status")
public class StatusController {

    private final LlmClient llmClient;
    private final EmbeddingClient embeddingClient;
    private final AnswerCache answerCache;
    private final IndexTaskQueue queue;
    private final RepoLock repoLock;
    private final RateLimiter rateLimiter;
    private final AsyncIndexer asyncIndexer;
    private final ReadCodeAiProperties properties;

    public StatusController(LlmClient llmClient, EmbeddingClient embeddingClient, AnswerCache answerCache,
                            IndexTaskQueue queue, RepoLock repoLock, RateLimiter rateLimiter,
                            AsyncIndexer asyncIndexer, ReadCodeAiProperties properties) {
        this.llmClient = llmClient;
        this.embeddingClient = embeddingClient;
        this.answerCache = answerCache;
        this.queue = queue;
        this.repoLock = repoLock;
        this.rateLimiter = rateLimiter;
        this.asyncIndexer = asyncIndexer;
        this.properties = properties;
    }

    /** 一个可降级组件的一句话状态：{@code ok=false} 就是"这一项现在不可用/已降级"。 */
    public record Component(String name, boolean ok, String detail) {
    }

    public record Jvm(String javaVersion, int processors, long maxHeapMb, long usedHeapMb) {

        static Jvm current() {
            Runtime runtime = Runtime.getRuntime();
            return new Jvm(System.getProperty("java.version"), runtime.availableProcessors(),
                    runtime.maxMemory() / (1024 * 1024), runtime.totalMemory() / (1024 * 1024));
        }
    }

    public record Status(Jvm jvm, java.util.List<Component> components) {
    }

    @GetMapping
    public ApiResponse<Status> status() {
        ReadCodeAiProperties.Queue queueProps = properties.getQueue();
        ReadCodeAiProperties.Lock lockProps = properties.getLock();
        ReadCodeAiProperties.RateLimit rateProps = properties.getRateLimit();

        java.util.List<Component> components = java.util.List.of(
                new Component("大模型（问答/摘要/审查）", llmClient.available(),
                        llmClient.available() ? "模型 " + llmClient.model()
                                : "未配置：确定性问题与静态分析照常可用，语义问答不可用"),
                new Component("向量化（第 3 层检索基线）", embeddingClient.available(), embeddingClient.describe()),
                new Component("答案缓存", answerCache.enabled(), answerCache.describe()),
                new Component("索引任务队列", true, queue.describe() + " · 排队 " + asyncIndexer.queued()
                        + " · 运行 " + asyncIndexer.running()),
                new Component("仓库锁（防同仓库并发索引）", repoLock.available(),
                        repoLock.describe() + (queueProps.getConcurrency() > 1 && !repoLock.available()
                                ? " ⚠️ 并发数 " + queueProps.getConcurrency() + " 但没有锁" : "")),
                new Component("接口限流（令牌桶）", rateLimiter.enabled(), rateLimiter.describe()),
                new Component("索引解析并行度", true, properties.getIndex().getParseThreads() == 0
                        ? "自动（核数与 8 取小）" : String.valueOf(properties.getIndex().getParseThreads())),
                new Component("中间件配置", true, "队列模式 " + queueProps.getMode()
                        + " · 并发 " + queueProps.getConcurrency()
                        + " · 锁 TTL " + lockProps.getTtlSeconds() + "s"
                        + " · 限流桶 " + rateProps.getCapacity() + "（每分钟补 " + rateProps.getRefillPerMinute() + "）"));
        return ApiResponse.ok(new Status(Jvm.current(), components));
    }
}
