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

    /** 启动时校验，非法值当场失败 —— 预算参数写错要到运行时才暴露，代价太大。 */
    @PostConstruct
    public void validate() {
        llm.validate();
        index.validate();
        retrieve.validate();
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

        void validate() {
            if (maxFileSizeKb <= 0) {
                throw new IllegalStateException("readcodeai.index.max-file-size-kb 必须大于 0");
            }
            if (workspace == null || workspace.isBlank()) {
                throw new IllegalStateException("readcodeai.index.workspace 不能为空");
            }
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
