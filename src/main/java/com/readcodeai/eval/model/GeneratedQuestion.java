package com.readcodeai.eval.model;

import java.util.List;

/**
 * 一道自动生成的评估题。
 *
 * <p>{@code truthKeys} 是**判卷用的规范位置键**，格式统一为 {@code "文件:起始行"}：
 * 符号类题取符号的声明起始行，调用类题取调用点所在行。
 * 统一成一种键，判卷就是纯粹的集合比较，不需要为每种题型写一套比较逻辑。
 *
 * <p>**这些键全部来自静态分析**（符号表 / 调用图 / 类型关系），不来自任何模型输出。
 */
public record GeneratedQuestion(
        QType type,
        String questionText,
        String payloadJson,
        List<String> truthKeys,
        String groundTruthJson) {
}
