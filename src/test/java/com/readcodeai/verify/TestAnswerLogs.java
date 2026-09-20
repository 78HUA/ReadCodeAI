package com.readcodeai.verify;

import com.readcodeai.agent.log.AnswerLogService;
import com.readcodeai.agent.log.AnswerLogSource;
import com.readcodeai.agent.model.AgentAnswer;
import com.readcodeai.agent.model.AskAnswer;
import com.readcodeai.config.ReadCodeAiProperties;

/**
 * 测试用的记账器：**占位，不落库**。
 *
 * <p>为什么单元测试不写真的流水：跑一次离线集会写几百行（评估集与两组对比实验都走同一条
 * 问答流水线），而「④ 指标」页上"累计问答多少次 / 花了多少 token"是**给人看的账** ——
 * 让测试数据去撑这些数字，等于把刚做出来的功能废掉。
 *
 * <p>记账**自己**的行为由 {@code AnswerLogTest} 验证：用真实仓库、真实的表，跑完自己清理。
 */
public final class TestAnswerLogs {

    private TestAnswerLogs() {
    }

    /**
     * 不落库的记账器。
     *
     * <p>repository 传 {@code null}：两个 record 方法都被覆盖成空，它永远不会被用到 ——
     * 这样测试不必为了"记账"再去要一个 JdbcTemplate。
     */
    public static AnswerLogService silent(ReadCodeAiProperties properties) {
        return new AnswerLogService(null, properties) {
            @Override
            public void recordAsk(Long repoId, Long questionId, AnswerLogSource source, String question,
                                  String mode, String routeJson, AskAnswer answer) {
                // 测试不记流水
            }

            @Override
            public void recordAgent(Long repoId, AnswerLogSource source, String question, String mode,
                                    String routeJson, boolean cacheHit, AgentAnswer answer) {
                // 测试不记流水
            }
        };
    }
}
