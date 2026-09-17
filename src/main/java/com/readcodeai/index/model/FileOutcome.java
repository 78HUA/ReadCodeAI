package com.readcodeai.index.model;

/** 单个源文件的解析结果。解析失败不中断整次索引，但必须留痕（parsedOk=false + error）。 */
public record FileOutcome(
        String relativePath,
        String contentHash,
        int loc,
        boolean parsedOk,
        String errorMessage) {
}
