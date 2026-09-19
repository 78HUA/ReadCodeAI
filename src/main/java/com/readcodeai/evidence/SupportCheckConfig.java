package com.readcodeai.evidence;

import com.readcodeai.config.LlmClient;
import com.readcodeai.config.ReadCodeAiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ③ 层核验器的装配：**关闭、或没配模型时，都退化成"不做判定"而不是"跳过校验"**（见 {@link NoopSupportChecker}）。
 *
 * <p>与答案缓存同一套纪律：这一层是"多花钱买确定性"的增强，
 * 拿不到时如实说"没做"，绝不能影响问答本身能不能返回。
 */
@Configuration
public class SupportCheckConfig {

    private static final Logger log = LoggerFactory.getLogger(SupportCheckConfig.class);

    @Bean
    SupportChecker supportChecker(LlmClient llmClient, ReadCodeAiProperties properties) {
        ReadCodeAiProperties.Verify.Mode mode = properties.getVerify().getSupportCheck();
        if (mode == ReadCodeAiProperties.Verify.Mode.OFF) {
            log.warn("③ 层核验已关闭（readcodeai.verify.support-check=off）："
                    + "证据只过 ①② 层（文件行号有效、片段与磁盘一致）");
            return new NoopSupportChecker("readcodeai.verify.support-check=off");
        }
        SupportChecker checker = new ModelSupportChecker(llmClient,
                mode == ReadCodeAiProperties.Verify.Mode.REJECT);
        log.info("③ 层核验已启用：{}", checker.describe());
        return checker;
    }
}
