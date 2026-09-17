package com.readcodeai.retrieve.model;

/**
 * 一个调用点。
 *
 * <p>两个文件字段是刻意分开的，别混用：
 * <ul>
 *   <li>{@code symbolFilePath} —— **对方符号**（查「谁调用我」时是调用者，查「我调用谁」时是被调用者）
 *       定义在哪个文件</li>
 *   <li>{@code callSiteFile} + {@code callLine} —— **调用发生在哪个文件的哪一行**</li>
 * </ul>
 * 两者在跨文件调用时不同，混用会让证据行号指错地方。
 */
public record CallSiteView(
        Long symbolId,
        String symbolName,
        String symbolQualifiedName,
        String signature,
        String symbolFilePath,
        String callSiteFile,
        int callLine,
        String callKind,
        String calleeRaw,
        boolean resolved,
        String reason) {

    public String callSiteLocation() {
        return callSiteFile + ":" + callLine;
    }
}
