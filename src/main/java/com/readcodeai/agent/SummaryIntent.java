package com.readcodeai.agent;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 「这个问题该不该直接交给结构化摘要」的判断 —— 总结类问题**不该走检索**。
 *
 * <p>为什么要有这条路："这个项目是干什么的"这种问题的答案**检索不出来**：
 * 它不是藏在某几段代码里，而是"结构算出来（模块划分、入口、枢纽）+ 语义模型补"两件事拼成的，
 * 那正是摘要能力的活。实测把这类问题丢给多跳的代价是：**93 秒、6 轮、16.5k token，
 * 最后模型输出 JSON 写坏了、按拒答返回**；而走摘要 15 秒就有一份带证据的答案。
 *
 * <p>判断方式是**词汇模式**，与 {@link ChainIntent} 同一套风格：可解释、可测、加一条规则就能复现一次回归。
 *
 * <p><b>取向与 ChainIntent 相反 —— 宁可不命中，也不要误伤</b>：链式问题漏判只是"答得浅一层"，
 * 而把「这个类是干什么的」误判成总结，就会去答一整个项目，那是答非所问。
 * 所以下面的模式**必须带"项目级"的名词**（项目/仓库/代码库/工程/系统/整体），光有"干什么"不算数。
 */
public final class SummaryIntent {

    /** 中文与英文都要覆盖（这个项目的主要使用者写中文）。 */
    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("(项目|仓库|代码库|工程|系统|整体).{0,8}(是做什么|做什么的|干什么|干嘛|"
                    + "有哪些功能|有什么功能|用途|是什么东西)"),
            Pattern.compile("(介绍|概述|概括|概览|说说|讲讲|总结).{0,6}(这个)?(项目|仓库|代码库|工程|系统)"),
            Pattern.compile("(项目|仓库|代码库|工程).{0,6}(介绍|概述|概览|全貌|架构|模块划分|有哪些模块)"),
            Pattern.compile("(整体|总体|大致).{0,4}(架构|结构|设计|情况|作用)"),
            Pattern.compile("有哪些模块|模块.{0,4}(划分|怎么分|如何分|有哪些)|分了哪些模块"),
            Pattern.compile("(what|whats|what's)\\s+(does|do|is)\\s+(this|the)\\s+.{0,12}"
                    + "(project|repo|repository|codebase)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(overview|summary|summarize|introduce)\\s+(of\\s+)?(this|the)?\\s*"
                    + "(project|repo|repository|codebase)", Pattern.CASE_INSENSITIVE));

    private SummaryIntent() {
    }

    public static boolean isSummaryQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        return PATTERNS.stream().anyMatch(pattern -> pattern.matcher(question).find());
    }
}
