package com.readcodeai.retrieve.model;

import java.time.LocalDateTime;

/** 已索引仓库的概览，含索引质量指标（解析成功率、调用解析率）。 */
public record RepoView(
        long id,
        String name,
        String rootPath,
        String commitHash,
        int fileCount,
        int parsedOkCount,
        int totalLoc,
        int symbolCount,
        int callEdgeCount,
        int callResolvedCount,
        String status,
        LocalDateTime indexedAt) {

    public double parseSuccessRate() {
        return fileCount == 0 ? 0 : (double) parsedOkCount / fileCount;
    }

    public double callResolveRate() {
        return callEdgeCount == 0 ? 0 : (double) callResolvedCount / callEdgeCount;
    }
}
