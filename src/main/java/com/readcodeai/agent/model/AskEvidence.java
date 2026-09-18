package com.readcodeai.agent.model;

/**
 * 答案里的一条证据。**没有证据的答案不许返回**，所以它是必填结构，不是附带信息。
 *
 * <p>{@code snippet} 是模型**逐字照抄**的那几行原文。它存在的唯一理由是**可核验**：
 * 校验器会拿它去磁盘上比对，对不上这条证据就作废。
 * **让模型知道"你引的代码会被核对"这件事本身，就是最便宜的防幻觉手段。**
 */
public record AskEvidence(String file, int startLine, int endLine, String snippet, String why) {

    public String location() {
        return file + ":" + startLine + "-" + endLine;
    }

    /** 便于在校验后替换错误的行号（定向修正）。 */
    public AskEvidence withRange(int newStart, int newEnd) {
        return new AskEvidence(file, newStart, newEnd, snippet, why);
    }
}
