package com.readcodeai.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 环检测：**同一条边不重复走**。
 *
 * <p>键是「工具名 + 规范化后的参数」，不是「符号」—— 因为一次查询的粒度就是一条边
 * （"谁调用了 X" 是一条边，重复查它只会得到同一个答案）。规范化的目的是让
 * {@code findCallers(R.success)}、{@code FIND_CALLERS ( R.success() )} 这类写法
 * 被判成同一个调用，否则模型换个大小写就能绕开检测。
 *
 * <p><b>为什么必须做</b>：调用图里本来就有环（互相调用、递归），
 * 而模型没有任何"我已经来过这里"的记忆 —— 不挡住它，多跳就会在两个方法之间来回跳，
 * 一路烧掉预算。挡不住环的多跳循环不是 Agent，是死循环。
 */
public class VisitedEdgeSet {

    private final Set<String> visited = new LinkedHashSet<>();

    /** @return true 表示这是第一次走这条边；false 表示已经走过 */
    public boolean firstVisit(String tool, String argsKey) {
        return visited.add(key(tool, argsKey));
    }

    public static String key(String tool, String argsKey) {
        return canonicalTool(tool) + "(" + normalize(argsKey) + ")";
    }

    /**
     * 工具名的归一：大小写、空白、下划线、连字符都不算区别
     * （模型写 {@code find_callers} 和 {@code findCallers} 说的是同一个工具）。
     *
     * <p><b>只对工具名做这一步，不对参数做</b>：参数里是符号名，
     * {@code a_b} 与 {@code ab} 是两个不同的方法，去掉下划线会把它们混成一条边。
     */
    static String canonicalTool(String tool) {
        return normalize(tool).replace("_", "").replace("-", "");
    }

    /** 去空白、转小写、去掉末尾的 {@code ()} —— 只为把同一次查询的几种写法归一。 */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String result = text.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        while (result.endsWith("()")) {
            result = result.substring(0, result.length() - 2);
        }
        return result;
    }

    public int size() {
        return visited.size();
    }

    /** 已走过的边（给提示词用：告诉模型"这些已经查过了"）。 */
    public List<String> labels(int limit) {
        List<String> all = new ArrayList<>(visited);
        return all.size() <= limit ? all : all.subList(0, limit);
    }
}
