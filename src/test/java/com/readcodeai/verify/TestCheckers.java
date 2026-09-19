package com.readcodeai.verify;

/**
 * 测试里统一使用的 ③ 层核验器：**不判定**。
 *
 * <p>为什么测试默认把它关掉：绝大多数用例钉的是**机制**（环检测、预算、证据核验、
 * 单跳与多跳的分工），而 ③ 层会多叫一次模型 —— 用脚本模型测机制时，那一次调用会把
 * 台词错位（脚本按次序念），断言随之失真。③ 层自己的用例在 {@code SupportCheckerTest}
 * 里显式换成"判定的"实现，那里才是它该被测的地方。
 *
 * <p>注意它返回的是 {@code NOT_CHECKED} 而不是 {@code SUPPORTED}：
 * 「没查」和「查过没问题」在代码里必须是两个状态（见 {@code SupportCheck}）。
 */
public final class TestCheckers {

    public static final com.readcodeai.evidence.SupportChecker NONE =
            new com.readcodeai.evidence.NoopSupportChecker("测试：本用例不判定 ③ 层");

    private TestCheckers() {
    }
}
