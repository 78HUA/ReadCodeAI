package com.readcodeai.config;

import com.readcodeai.config.BudgetGuard.Dimension;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 四维预算的单测：**每一维都要能单独把循环停下**，而且停下来的原因要能说清是哪一个。
 *
 * <p>不连数据库、不调模型 —— 预算就是几个计数器和比较，测它不需要任何外部依赖。
 * 换个角度说：如果连这个都要靠"跑一遍真实多跳看看会不会失控"来验证，那说明闸门本身不可信。
 */
class BudgetGuardTest {

    private static BudgetGuard guard(int maxRounds, long maxDurationMs, long maxTokens, double maxCost) {
        return new BudgetGuard(maxRounds, maxDurationMs, maxTokens, maxCost, 0, 0);
    }

    @Test
    void stopsOnRounds() {
        BudgetGuard budget = guard(2, 60_000, 1_000_000, 100);
        assertThat(budget.canContinue()).isTrue();
        budget.recordLlmCall(10, 10);
        assertThat(budget.canContinue()).isTrue();
        budget.recordLlmCall(10, 10);
        assertThat(budget.canContinue()).as("第 2 轮之后达到轮次上限").isFalse();
        assertThat(budget.stopReason()).contains(Dimension.ROUNDS);
        assertThat(budget.usage().rounds()).isEqualTo(2);
    }

    @Test
    void stopsOnTokens() {
        BudgetGuard budget = guard(10, 60_000, 2_500, 100);
        budget.recordLlmCall(1_000, 0);
        budget.recordLlmCall(1_000, 0);
        assertThat(budget.canContinue()).isTrue();
        budget.recordLlmCall(1_000, 0);
        assertThat(budget.canContinue()).as("3000 token 已超过 2500 的上限").isFalse();
        assertThat(budget.stopReason()).contains(Dimension.TOKENS);
        assertThat(budget.usage().totalTokens()).isEqualTo(3_000);
    }

    @Test
    void stopsOnCostUsingTheConfiguredPrices() {
        // 输入 10 元/百万 token：调用 20 万 token 就是 2 元，超过 1 元的上限
        BudgetGuard budget = new BudgetGuard(10, 60_000, 10_000_000, 1.0, 10.0, 0);
        budget.recordLlmCall(200_000, 0);
        assertThat(budget.estimatedCost()).isCloseTo(2.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(budget.canContinue()).isFalse();
        assertThat(budget.stopReason()).contains(Dimension.COST);
    }

    @Test
    void stopsOnDuration() throws InterruptedException {
        BudgetGuard budget = guard(10, 30, 1_000_000, 100);
        Thread.sleep(60);
        assertThat(budget.canContinue()).isFalse();
        assertThat(budget.stopReason()).contains(Dimension.DURATION);
    }

    @Test
    void keepsTheFirstStopReasonInsteadOfOverwritingIt() {
        BudgetGuard budget = guard(1, 30, 1_000_000, 100);
        budget.recordLlmCall(1, 1);
        assertThat(budget.canContinue()).isFalse();
        assertThat(budget.stopReason()).contains(Dimension.ROUNDS);
        assertThat(budget.canContinue()).as("反复询问不该改变已记录的原因").isFalse();
        assertThat(budget.stopReason()).contains(Dimension.ROUNDS);
    }

    @Test
    void freePricingMeansTheCostGateCannotFire() {
        // 免费档单价为 0 → 成本恒为 0，闸门形同不存在。这是配置的性质，不是 bug，
        // 但必须心里有数：换成付费档（非 0 单价）它才会真正起作用。
        BudgetGuard budget = guard(10, 60_000, 1_000_000_000L, 0.5);
        budget.recordLlmCall(5_000_000, 5_000_000);
        assertThat(budget.estimatedCost()).isZero();
        assertThat(budget.canContinue()).as("只有成本这一维会拦它，而它恒为 0").isTrue();
    }

    @Test
    void countsRepeatedToolCallsSeparately() {
        BudgetGuard budget = guard(10, 60_000, 1_000_000, 100);
        budget.recordToolCall(false);
        budget.recordToolCall(false);
        budget.recordToolCall(true);
        assertThat(budget.usage().toolCalls()).as("真正执行的跳数").isEqualTo(2);
        assertThat(budget.usage().repeatedCalls()).as("被环检测拦下的次数").isEqualTo(1);
    }
}
