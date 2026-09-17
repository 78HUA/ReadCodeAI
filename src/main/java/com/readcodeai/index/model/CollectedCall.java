package com.readcodeai.index.model;

/**
 * 一条调用边。
 *
 * <p>{@code resolved=false} 时 {@code calleeSymbolKey} 为 null，但 {@code calleeRaw} 一定保留 ——
 * 「没解析出来」和「不存在」必须能区分开，否则准确率报告就是自欺。
 */
public record CollectedCall(
        String callerSymbolKey,
        String calleeSymbolKey,
        String calleeRaw,
        int line,
        String callKind,
        boolean resolved,
        String reason) {
}
