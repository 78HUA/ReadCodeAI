package com.readcodeai.agent;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 「这个问题值不值得多跳」的判断。
 *
 * <p>单跳检索**答不了**这几类问题：问"间接调用者"、"完整调用链"、"这个参数从哪来"、
 * "改了这个方法会影响哪些地方" —— 它们的答案要沿调用图跳几跳才拿得到。
 * 这就是多跳存在的理由，也是第三组对比实验要量出来的差异。
 *
 * <p>判断方式是**词汇模式**，不是语义理解：可解释、可测、加一条规则就能复现一次回归。
 * 拿不准时**倾向走多跳** —— 多跳有预算闸门兜着，最坏情况是贵一点；
 * 而单跳对这类问题是根本答不了，代价是"看起来答了，其实只答了一层"。
 */
public final class ChainIntent {

    /** 中文与英文都要覆盖（这个项目的主要使用者写中文）。 */
    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("间接|直接或间接"),
            Pattern.compile("调用链|链路上|上游|向下游|下游"),
            Pattern.compile("最终.{0,4}调用|一路|追溯到|追到哪"),
            Pattern.compile("从哪来|从哪来|哪里来的|谁传进来|参数.{0,6}(来源|由谁)"),
            Pattern.compile("影响面|会影响哪|改.{0,8}影响"),
            Pattern.compile("完整.{0,4}(链路|路径|流程)|整条链"),
            Pattern.compile("transitive|call\\s*chain|upstream|downstream|who\\s+ends?\\s+up", Pattern.CASE_INSENSITIVE));

    private ChainIntent() {
    }

    public static boolean isChainQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        return PATTERNS.stream().anyMatch(pattern -> pattern.matcher(question).find());
    }
}
