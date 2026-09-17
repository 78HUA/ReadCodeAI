package com.readcodeai.index.model;

/**
 * 一个待落库的检索单元（chunk）。
 *
 * <p><b>按符号切，不按行切</b> —— 按固定行数切会把一个方法劈成两半，
 * 检索到上半段时模型看不到返回逻辑，于是自信地给出错误结论。
 *
 * <p>{@code content} 是从源文件里**原样切出来**的文本（不是拼接生成的），
 * 这样它才能被当作证据：行号与内容都能回磁盘核对。
 */
public record CollectedChunk(
        String filePath,
        String kind,
        String symbolKey,
        int startLine,
        int endLine,
        String contentHash,
        String content,
        int tokenEstimate) {

    public static final String KIND_SYMBOL = "SYMBOL";
    public static final String KIND_FILE_HEADER = "FILE_HEADER";
}
