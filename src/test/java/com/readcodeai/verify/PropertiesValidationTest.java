package com.readcodeai.verify;

import com.readcodeai.config.ReadCodeAiProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 预算类参数写错，必须在启动时就失败 —— 否则等到多跳检索跑到一半才发现上限是 0，
 * 排查成本高得多。
 */
class PropertiesValidationTest {

    @Test
    void acceptsTheDefaults() {
        assertThatCode(new ReadCodeAiProperties()::validate).doesNotThrowAnyException();
    }

    @Test
    void rejectsZeroRounds() {
        ReadCodeAiProperties properties = new ReadCodeAiProperties();
        properties.getLlm().setMaxRounds(0);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-rounds");
    }

    @Test
    void rejectsNegativeDuration() {
        ReadCodeAiProperties properties = new ReadCodeAiProperties();
        properties.getLlm().setMaxDurationMs(-1);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-duration-ms");
    }

    @Test
    void rejectsZeroTokenBudget() {
        ReadCodeAiProperties properties = new ReadCodeAiProperties();
        properties.getLlm().setMaxEstimatedTokens(0);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-estimated-tokens");
    }

    @Test
    void rejectsNonPositiveTimeout() {
        ReadCodeAiProperties properties = new ReadCodeAiProperties();
        properties.getLlm().setTimeoutSeconds(0);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timeout-seconds");
    }
}
