package com.readcodeai.index;

import java.util.Map;

/** 一次索引的实测产出。所有数字都来自实际执行，用于回填验证记录。 */
public record IndexSummary(
        long repoId,
        String name,
        String rootPath,
        String commitHash,
        int fileCount,
        int parsedOkCount,
        int totalLoc,
        int symbolCount,
        int callEdgeCount,
        int callResolvedCount,
        int orphanEdgeCount,
        Map<String, Integer> unresolvedCallReasons,
        long parseMillis,
        long resolveMillis,
        long storeMillis,
        long totalMillis) {

    public double callResolveRate() {
        return callEdgeCount == 0 ? 0 : (double) callResolvedCount / callEdgeCount;
    }

    public double parseSuccessRate() {
        return fileCount == 0 ? 0 : (double) parsedOkCount / fileCount;
    }

    /** 人可读的实测报告，直接贴进验证记录。 */
    public String toReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 索引实测 ===").append(System.lineSeparator())
                .append("仓库        : ").append(name).append("  (").append(rootPath).append(')')
                .append(System.lineSeparator())
                .append("提交号      : ").append(commitHash == null ? "(未取到)" : commitHash)
                .append(System.lineSeparator())
                .append("Java 文件   : ").append(fileCount)
                .append("  解析成功 ").append(parsedOkCount)
                .append(String.format(" (%.2f%%)%n", parseSuccessRate() * 100))
                .append("代码行数    : ").append(totalLoc).append(System.lineSeparator())
                .append("符号数      : ").append(symbolCount).append(System.lineSeparator())
                .append("调用边      : ").append(callEdgeCount)
                .append("  已解析 ").append(callResolvedCount)
                .append(String.format(" (%.2f%%)%n", callResolveRate() * 100))
                .append("悬挂边      : ").append(orphanEdgeCount).append("（调用者符号缺失，应为 0）")
                .append(System.lineSeparator())
                .append("未解析原因  :").append(System.lineSeparator());
        if (unresolvedCallReasons.isEmpty()) {
            sb.append("  （无）").append(System.lineSeparator());
        } else {
            unresolvedCallReasons.forEach((reason, count) ->
                    sb.append("  ").append(String.format("%-6d", count)).append(' ').append(reason)
                            .append(System.lineSeparator()));
        }
        sb.append("耗时        : 解析 ").append(parseMillis).append(" ms · 解析调用 ")
                .append(resolveMillis).append(" ms · 落库 ").append(storeMillis)
                .append(" ms · 合计 ").append(totalMillis).append(" ms");
        return sb.toString();
    }
}
