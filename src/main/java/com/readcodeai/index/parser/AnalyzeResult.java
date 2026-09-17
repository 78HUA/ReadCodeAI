package com.readcodeai.index.parser;

import com.readcodeai.index.model.CollectedCall;
import com.readcodeai.index.model.CollectedChunk;
import com.readcodeai.index.model.CollectedSymbol;
import com.readcodeai.index.model.CollectedTypeRelation;
import com.readcodeai.index.model.FileOutcome;

import java.util.List;

/**
 * 一次完整分析的产物。
 *
 * <p>{@code parseFailureReasonCounts} 单独返回：解析失败的原因分布本身就是结论
 * （要如实报告「哪里没解析出来、为什么」），不只是日志。
 */
public record AnalyzeResult(
        List<FileOutcome> files,
        List<CollectedSymbol> symbols,
        List<CollectedCall> calls,
        List<CollectedTypeRelation> relations,
        List<CollectedChunk> chunks,
        long parseMillis,
        long resolveMillis) {

    public long parsedOkCount() {
        return files.stream().filter(FileOutcome::parsedOk).count();
    }

    public long resolvedCallCount() {
        return calls.stream().filter(CollectedCall::resolved).count();
    }
}
