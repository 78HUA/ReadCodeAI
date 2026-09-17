package com.readcodeai.index.model;

/**
 * 一个待落库的符号。
 *
 * <p>{@code qualifiedName} 是**全仓库唯一的检索键**，格式：
 * 类型 = 包名.类名（嵌套类型用点继续拼）；方法/构造器 = 类型#名字/参数个数；
 * 字段 = 类型.字段名。用「参数个数」而不是完整参数类型，是为了和符号求解器报出来的
 * 声明信息能稳定对上 —— 两边各自的类型字符串格式不一致时要对不上。
 *
 * <p>{@code signature} 只用于展示，是给人看的。
 */
public record CollectedSymbol(
        String kind,
        String name,
        String qualifiedName,
        String filePath,
        String signature,
        String parentQualifiedName,
        int startLine,
        int endLine,
        String modifiers,
        String returnType,
        String javadoc) {
}
