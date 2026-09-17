package com.readcodeai.retrieve.model;

/**
 * 一条全文检索命中。
 *
 * <p>带 {@code filePath} + 起止行 + {@code content}，因为**检索结果最终要变成证据**：
 * 行号与内容都要能回磁盘核对，这是本项目对每一条输出的要求。
 */
public record ChunkHit(
        long chunkId,
        String kind,
        String filePath,
        int startLine,
        int endLine,
        Long symbolId,
        String symbolQualifiedName,
        int tokenEstimate,
        double score,
        String content) {

    public String location() {
        return filePath + ":" + startLine + "-" + endLine;
    }
}
