package com.readcodeai.retrieve.model;

/**
 * 符号的展示视图。字段与 {@code symbol} 表对应，额外带上文件路径 ——
 * 输出**必须能直接给出「文件 + 行号」**，这是本项目的证据要求，不是可选装饰。
 */
public record SymbolView(
        long id,
        String kind,
        String name,
        String qualifiedName,
        String signature,
        String filePath,
        int startLine,
        int endLine,
        String modifiers,
        String returnType) {

    /** 人类可读的定位串，例如 {@code a/b/Foo.java:42-58}。 */
    public String location() {
        return filePath + ":" + startLine + "-" + endLine;
    }
}
