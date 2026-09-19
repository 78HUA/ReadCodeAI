package com.readcodeai.index.model;

import java.time.LocalDateTime;

/**
 * 索引任务的一条记录 —— 异步索引的"任务单"。
 *
 * @param repoId  仓库行创建后才有（拉取/解压阶段还没有仓库）
 * @param status  QUEUED / RUNNING / READY / FAILED
 * @param stage   当前在哪个阶段（PARSING 时 {@code done/total} 就是"已解析 N/M 个文件"）
 */
public record IndexJob(
        long id,
        Long repoId,
        String kind,
        String source,
        String status,
        String stage,
        int done,
        int total,
        String message,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public int percent() {
        return total <= 0 ? 0 : (int) Math.min(100, done * 100L / total);
    }
}
