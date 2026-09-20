package com.readcodeai.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 总结意图的判据：**宁可漏判，不要误伤**。
 *
 * <p>取向与 {@link ChainIntent} 相反，理由是不对称的代价：链式问题漏判最多是"答得浅一层"，
 * 而把「这个类是干什么的」误判成总结，就会去答一整个项目 —— 那是答非所问。
 * 所以下面两组的边界要卡死：**必须有"项目级"的名词**才算数。
 */
class SummaryIntentTest {

    @Test
    void matchesProjectLevelQuestions() {
        List<String> positives = List.of(
                "这个项目是干什么的？",
                "这个仓库是做什么的",
                "介绍一下这个项目",
                "这个项目的整体架构是怎样的",
                "这个项目有哪些模块？",
                "模块是怎么划分的",
                "what does this project do?",
                "overview of this repository");
        positives.forEach(question -> assertThat(SummaryIntent.isSummaryQuestion(question))
                .as("应当命中总结意图：%s", question).isTrue());
    }

    @Test
    void doesNotMatchSymbolLevelQuestions() {
        List<String> negatives = List.of(
                "这个类是干什么的？",
                "这个方法做什么的",
                "谁调用了 AnswerService 的 ask 方法？",
                "AnswerService.ask 定义在哪？",
                "这个项目里谁调用了 resolve 方法？",
                "上传文件的接口在哪个类里？",
                "改这个方法会影响哪些地方？",
                "这个参数是从哪来的？");
        negatives.forEach(question -> assertThat(SummaryIntent.isSummaryQuestion(question))
                .as("不该命中总结意图：%s", question).isFalse());
    }

    @Test
    void blankAndNullAreNotSummaryQuestions() {
        assertThat(SummaryIntent.isSummaryQuestion(null)).isFalse();
        assertThat(SummaryIntent.isSummaryQuestion("   ")).isFalse();
    }
}
