package com.readcodeai.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 全部可配置项。约定：密钥类只从环境变量读（见 application.yml 里的占位符），不写进配置文件。
 */
@ConfigurationProperties(prefix = "readcodeai")
public class ReadCodeAiProperties {

    private final Llm llm = new Llm();
    private final Index index = new Index();
    private final Retrieve retrieve = new Retrieve();
    private final Cache cache = new Cache();
    private final Verify verify = new Verify();
    private final Embedding embedding = new Embedding();
    private final Queue queue = new Queue();
    private final Lock lock = new Lock();
    private final RateLimit rateLimit = new RateLimit();

    /** 启动时校验，非法值当场失败 —— 预算参数写错要到运行时才暴露，代价太大。 */
    @PostConstruct
    public void validate() {
        llm.validate();
        index.validate();
        retrieve.validate();
        cache.validate();
        verify.validate();
        embedding.validate();
        queue.validate();
        lock.validate();
        rateLimit.validate();
    }

    public Llm getLlm() {
        return llm;
    }

    public Index getIndex() {
        return index;
    }

    public Retrieve getRetrieve() {
        return retrieve;
    }

    public Cache getCache() {
        return cache;
    }

    public Verify getVerify() {
        return verify;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public Queue getQueue() {
        return queue;
    }

    public Lock getLock() {
        return lock;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    /**
     * 会调模型的接口的限流（令牌桶，Redis + Lua）。
     *
     * <p>限的是**稀缺资源**（模型额度与时间），不是"显得专业"：一次问答几千 token、几十秒，
     * 没有闸门时一个循环脚本就能把额度打满。符号查询 / 全文检索这些本地操作不限。
     */
    public static class RateLimit {

        /** 关掉即不限流；Redis 不可用时也会自动放行（保护件不该升级成全站故障）。 */
        private boolean enabled = true;

        /** 桶容量：允许的突发量（连着问几个问题不会被立刻拒）。 */
        private int capacity = 10;

        /** 每分钟补充多少令牌 = 稳态速率上限。 */
        private int refillPerMinute = 20;

        void validate() {
            if (capacity <= 0) {
                throw new IllegalStateException("readcodeai.rate-limit.capacity 必须大于 0");
            }
            if (refillPerMinute <= 0) {
                throw new IllegalStateException("readcodeai.rate-limit.refill-per-minute 必须大于 0");
            }
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public int getRefillPerMinute() {
            return refillPerMinute;
        }

        public void setRefillPerMinute(int refillPerMinute) {
            this.refillPerMinute = refillPerMinute;
        }
    }

    /**
     * 索引的**按仓库路径互斥锁**（Redis）。
     *
     * <p>它防的不是"同一个任务跑两次"（那是幂等管的），而是**同一个仓库路径被提交两次**：
     * 两条不同的任务同时索引同一路径会互相"删了再插"，那是真会坏数据的。
     * 单 worker 时不会发生（一个任务一个任务来），消费者并发数调到 2 以上才成为现实问题 ——
     * 所以它跟"多 worker"是同一步的配套改动。
     */
    public static class Lock {

        /** 关掉即退化为"不互斥"（单 worker 下没区别；多 worker + 关掉 = 自己承担并发索引同一仓库的风险）。 */
        private boolean enabled = true;

        /** 锁的 TTL：必须大于"一次索引"的耗时，否则锁会在索引中途过期。 */
        private long ttlSeconds = 600;

        /** 续期间隔：持有锁期间按这个间隔延长 TTL（索引可能跑几分钟）。 */
        private long refreshSeconds = 180;

        /** 等锁的超时：别人正在索引同一路径时最多等这么久，超时就失败并说清原因。 */
        private long waitSeconds = 180;

        void validate() {
            if (ttlSeconds <= 0 || refreshSeconds <= 0 || waitSeconds <= 0) {
                throw new IllegalStateException("readcodeai.lock.* 的三个秒数都必须大于 0");
            }
            if (refreshSeconds >= ttlSeconds) {
                throw new IllegalStateException("readcodeai.lock.refresh-seconds 必须小于 ttl-seconds，"
                        + "否则续期来不及（锁会在索引中途过期）");
            }
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        public long getRefreshSeconds() {
            return refreshSeconds;
        }

        public void setRefreshSeconds(long refreshSeconds) {
            this.refreshSeconds = refreshSeconds;
        }

        public long getWaitSeconds() {
            return waitSeconds;
        }

        public void setWaitSeconds(long waitSeconds) {
            this.waitSeconds = waitSeconds;
        }
    }

    /**
     * 索引任务的投递方式。
     *
     * <p>默认 {@link Mode#IN_PROCESS}：**没有 broker 的机器上项目照常跑**（与"没配 LLM / 没 Redis
     * 也能跑"同一条纪律）。换 {@link Mode#RABBIT} 买到的是：任务持久化、重启后自动续跑、
     * 消费者可扩到多个 —— 也就是 README 已知限制第 8 条那笔账。
     */
    public static class Queue {

        public enum Mode {
            /** 进程内队列（默认）：零依赖，代价是重启丢任务 */
            IN_PROCESS,
            /** RabbitMQ：任务不丢 + 可扩消费者 */
            RABBIT
        }

        private Mode mode = Mode.IN_PROCESS;

        private String name = "readcodeai.index.jobs";

        /** 死信队列：消费失败的消息落到这里，而不是无限重投（要能一眼看见"哪些任务炸了"）。 */
        private String dlqName = "readcodeai.index.jobs.dlq";

        /**
         * 消费者并发数（= 同时跑几个索引）。
         *
         * <p>默认 1（保守）。实测（2026-09-20）：三个仓库排队时并发 3 比并发 1 快约 1.6 倍
         * （38 秒 → 24 秒）—— 早先"并发只会互相拖慢"的结论只在**单任务内**成立。
         * 调大之前先确认仓库锁可用（{@code readcodeai.lock.enabled}）：多 worker + 无锁 = 同一仓库可能被并发索引。
         */
        private int concurrency = 1;

        /** broker 探活超时：连不上就明确降级并告警，而不是让提交请求挂住。 */
        private int connectTimeoutMs = 2000;

        void validate() {
            if (mode == null) {
                throw new IllegalStateException("readcodeai.queue.mode 只能是 in-process / rabbit");
            }
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("readcodeai.queue.name 不能为空");
            }
            if (dlqName == null || dlqName.isBlank() || dlqName.equals(name)) {
                throw new IllegalStateException("readcodeai.queue.dlq-name 不能为空、也不能和主队列同名");
            }
            if (concurrency <= 0) {
                throw new IllegalStateException("readcodeai.queue.concurrency 必须大于 0");
            }
        }

        public Mode getMode() {
            return mode;
        }

        public void setMode(Mode mode) {
            this.mode = mode;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDlqName() {
            return dlqName;
        }

        public void setDlqName(String dlqName) {
            this.dlqName = dlqName;
        }

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }
    }

    /**
     * 向量检索基线（第 3 层检索）。
     *
     * <p>定位要说清楚：它是**对比实验的基线 + 将来"模糊语义查找"的接口位**，
     * 没有接进问答路由 —— 确定性问题走符号表/调用图更准，这个判断不因为有了向量而改变。
     * 与 LLM **共用 base-url 与 api-key**（同一把 Key、同一个供应商），所以这里没有自己的连接配置。
     */
    public static class Embedding {

        /** 关掉即降级为 Noop：索引与问答完全不受影响，只是向量检索与对比实验不可用。 */
        private boolean enabled = true;

        /** 换模型 = 换向量空间，缓存的向量全部作废（键里带 model 就是为此）。 */
        private String model = "embedding-3";

        /**
         * 向量维度。实测 embedding-3 支持 dimensions 参数：默认 2048，指定 1024 省一半存储与内存，
         * 召回差异交给对比实验去量（见 verification-log）。
         */
        private int dimensions = 1024;

        /** 一次请求最多带多少条输入（客户端不做二次分包，批的大小由调用方控制）。 */
        private int batchSize = 16;

        void validate() {
            if (dimensions <= 0) {
                throw new IllegalStateException("readcodeai.embedding.dimensions 必须大于 0");
            }
            if (batchSize <= 0) {
                throw new IllegalStateException("readcodeai.embedding.batch-size 必须大于 0");
            }
            if (model == null || model.isBlank()) {
                throw new IllegalStateException("readcodeai.embedding.model 不能为空");
            }
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getDimensions() {
            return dimensions;
        }

        public void setDimensions(int dimensions) {
            this.dimensions = dimensions;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
    }

    /**
     * 证据校验里唯一需要模型的那一层（③：这段代码**支持**这条结论吗）。
     *
     * <p>①② 层（文件行号有效性、片段与磁盘一致）是程序化比对、零成本，不受这里影响、也没法关。
     * 这一层因为要额外调一次模型，默认做成「标记」而不是「拒答」—— 理由与实测数字见
     * {@link com.readcodeai.agent.model.SupportCheck}。
     */
    public static class Verify {

        public enum Mode {
            /** 不做判定：省一次模型调用，①② 层照常 */
            OFF,
            /** 做判定，判成"不支持"时**保留答案并显著标出**（默认：判定会有误伤，不该把好答案丢掉） */
            MARK,
            /** 做判定，判成"不支持"时拒答（更严格，代价是误伤直接变成丢答案） */
            REJECT
        }

        private Mode supportCheck = Mode.MARK;

        void validate() {
            if (supportCheck == null) {
                throw new IllegalStateException("readcodeai.verify.support-check 只能是 off / mark / reject");
            }
        }

        public Mode getSupportCheck() {
            return supportCheck;
        }

        public void setSupportCheck(Mode supportCheck) {
            this.supportCheck = supportCheck;
        }

        /** 判定完成、但结论不被支持时，要不要按拒答处理。 */
        public boolean rejectOnUnsupported() {
            return supportCheck == Mode.REJECT;
        }
    }

    /**
     * 答案缓存（Redis）。
     *
     * <p>只缓存**模型给出的**答案：确定性问题（定位/调用关系）本来就只有几毫秒，
     * 缓存它既没有收益、又增加"拿到过期结果"的风险。
     */
    public static class Cache {

        /** 关掉它、或 Redis 连不上，都只是"每次都真算一遍"，问答本身不受影响（可降级）。 */
        private boolean enabled = true;

        private long answerTtlMinutes = 1440;

        void validate() {
            if (answerTtlMinutes <= 0) {
                throw new IllegalStateException("readcodeai.cache.answer-ttl-minutes 必须大于 0");
            }
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getAnswerTtlMinutes() {
            return answerTtlMinutes;
        }

        public void setAnswerTtlMinutes(long answerTtlMinutes) {
            this.answerTtlMinutes = answerTtlMinutes;
        }
    }

    public static class Llm {

        /** 关掉它或没配 api-key，都会降级为 NoopLlmClient（静态分析能力不受影响）。 */
        private boolean enabled = true;
        private String baseUrl = "";
        private String apiKey = "";
        private String model = "";
        private int timeoutSeconds = 60;

        /**
         * 多跳检索的四维预算：轮次 / 时长 / token / 成本，任一超限即停。
         *
         * <p><b>注意轮次的算法</b>：最后一轮是留给结论的（模型在这一轮若还要调工具，就直接停机并交出轨迹），
         * 所以 {@code maxRounds = 8} 实际最多查 6 跳。
         * 默认值从 3 提到 8 是**前端实测逼出来的**：3 轮意味着只能查 1 跳，
         * 界面上看着就是"多跳检索没什么用"。
         */
        private int maxRounds = 8;
        private long maxDurationMs = 60_000;
        private long maxEstimatedTokens = 60_000;
        private double maxEstimatedCost = 0.5;

        /**
         * 多跳的提示词里保留多少跳的**原始输出**；更早的轮次压成一行事实（跳数 + 查到哪些名字 + 证据位置）。
         *
         * <p>为什么要它：每轮都要把整份记录重发，旧轮次的代码文本占了绝大部分，
         * token 随轮数近似平方增长（实测单题 6k–15k token）。压掉代码文本、留下名字与位置，
         * 模型该有的推理骨架一条不少。
         *
         * <p>{@code 0} = 完全不压缩（旧行为），保留它是为了 A/B 对照和"万一压坏了"的逃生门。
         */
        private int keepFullObservations = 2;

        /** 计价参数，仅用于估算成本，不影响调用。 */
        private double inputPricePerMillion = 0;
        private double outputPricePerMillion = 0;

        void validate() {
            requirePositive(timeoutSeconds, "readcodeai.llm.timeout-seconds");
            requirePositive(maxRounds, "readcodeai.llm.max-rounds");
            requirePositive(maxDurationMs, "readcodeai.llm.max-duration-ms");
            requirePositive(maxEstimatedTokens, "readcodeai.llm.max-estimated-tokens");
            requirePositive(maxEstimatedCost, "readcodeai.llm.max-estimated-cost");
            if (inputPricePerMillion < 0 || outputPricePerMillion < 0) {
                throw new IllegalStateException("readcodeai.llm 的计价参数不能为负数");
            }
            if (keepFullObservations < 0) {
                // 0 是合法值（= 不压缩，旧行为），负数没有意义
                throw new IllegalStateException("readcodeai.llm.keep-full-observations 不能为负数，"
                        + "0 表示不压缩，当前为 " + keepFullObservations);
            }
        }

        private static void requirePositive(long value, String key) {
            if (value <= 0) {
                throw new IllegalStateException(key + " 必须大于 0，当前为 " + value);
            }
        }

        private static void requirePositive(double value, String key) {
            if (value <= 0) {
                throw new IllegalStateException(key + " 必须大于 0，当前为 " + value);
            }
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public int getMaxRounds() {
            return maxRounds;
        }

        public void setMaxRounds(int maxRounds) {
            this.maxRounds = maxRounds;
        }

        public int getKeepFullObservations() {
            return keepFullObservations;
        }

        public void setKeepFullObservations(int keepFullObservations) {
            this.keepFullObservations = keepFullObservations;
        }

        public long getMaxDurationMs() {
            return maxDurationMs;
        }

        public void setMaxDurationMs(long maxDurationMs) {
            this.maxDurationMs = maxDurationMs;
        }

        public long getMaxEstimatedTokens() {
            return maxEstimatedTokens;
        }

        public void setMaxEstimatedTokens(long maxEstimatedTokens) {
            this.maxEstimatedTokens = maxEstimatedTokens;
        }

        public double getMaxEstimatedCost() {
            return maxEstimatedCost;
        }

        public void setMaxEstimatedCost(double maxEstimatedCost) {
            this.maxEstimatedCost = maxEstimatedCost;
        }

        public double getInputPricePerMillion() {
            return inputPricePerMillion;
        }

        public void setInputPricePerMillion(double inputPricePerMillion) {
            this.inputPricePerMillion = inputPricePerMillion;
        }

        public double getOutputPricePerMillion() {
            return outputPricePerMillion;
        }

        public void setOutputPricePerMillion(double outputPricePerMillion) {
            this.outputPricePerMillion = outputPricePerMillion;
        }
    }

    public static class Index {

        /** 超过这个大小的源文件跳过并记录，不让单个巨型文件拖垮整次索引。 */
        private int maxFileSizeKb = 2048;

        /**
         * 远程仓库拉取后的存放目录。
         * 默认放在用户目录下而不是项目目录里 —— 免得把下载的第三方代码混进仓库工作区。
         */
        private String workspace = System.getProperty("user.home") + "/.readcodeai/repos";

        private java.util.List<String> excludePatterns = new java.util.ArrayList<>(
                java.util.List.of("**/target/**", "**/build/**", "**/generated/**"));

        /**
         * 解析文件的并行度：{@code 0} = 自动（核数与 8 取小），{@code 1} = 串行。
         *
         * <p>为什么要留串行这个挡位：它是**并行改动的对照组**（同一个 JVM 里先跑串行再跑并行，
         * 排除 JIT 与磁盘缓存的干扰），也是"万一并行出怪事"的逃生门。
         */
        private int parseThreads = 0;

        void validate() {
            if (maxFileSizeKb <= 0) {
                throw new IllegalStateException("readcodeai.index.max-file-size-kb 必须大于 0");
            }
            if (workspace == null || workspace.isBlank()) {
                throw new IllegalStateException("readcodeai.index.workspace 不能为空");
            }
            if (parseThreads < 0) {
                throw new IllegalStateException("readcodeai.index.parse-threads 不能为负数"
                        + "（0 = 自动，1 = 串行），当前为 " + parseThreads);
            }
        }

        public int getParseThreads() {
            return parseThreads;
        }

        public void setParseThreads(int parseThreads) {
            this.parseThreads = parseThreads;
        }

        public String getWorkspace() {
            return workspace;
        }

        public void setWorkspace(String workspace) {
            this.workspace = workspace;
        }

        public int getMaxFileSizeKb() {
            return maxFileSizeKb;
        }

        public void setMaxFileSizeKb(int maxFileSizeKb) {
            this.maxFileSizeKb = maxFileSizeKb;
        }

        public java.util.List<String> getExcludePatterns() {
            return excludePatterns;
        }

        public void setExcludePatterns(java.util.List<String> excludePatterns) {
            this.excludePatterns = excludePatterns;
        }
    }

    public static class Retrieve {

        private int topK = 8;

        /** 塞进模型的上下文上限，防止把整个仓库灌进去。 */
        private long maxContextTokens = 30_000;

        /**
         * 同一个文件最多贡献几个代码块。
         * 没有这个上限，一个到处被引用的热门文件会霸占整个上下文，把真正的答案挤出预算。
         */
        private int maxChunksPerFile = 3;

        void validate() {
            if (topK <= 0) {
                throw new IllegalStateException("readcodeai.retrieve.top-k 必须大于 0");
            }
            if (maxContextTokens <= 0) {
                throw new IllegalStateException("readcodeai.retrieve.max-context-tokens 必须大于 0");
            }
            if (maxChunksPerFile <= 0) {
                throw new IllegalStateException("readcodeai.retrieve.max-chunks-per-file 必须大于 0");
            }
        }

        public int getMaxChunksPerFile() {
            return maxChunksPerFile;
        }

        public void setMaxChunksPerFile(int maxChunksPerFile) {
            this.maxChunksPerFile = maxChunksPerFile;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public long getMaxContextTokens() {
            return maxContextTokens;
        }

        public void setMaxContextTokens(long maxContextTokens) {
            this.maxContextTokens = maxContextTokens;
        }
    }
}
