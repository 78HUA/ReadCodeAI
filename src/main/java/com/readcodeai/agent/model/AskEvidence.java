package com.readcodeai.agent.model;

/** 答案里的一条证据。**没有证据的答案不许返回**，所以它是必填结构，不是附带信息。 */
public record AskEvidence(String file, int startLine, int endLine, String why) {

    public String location() {
        return file + ":" + startLine + "-" + endLine;
    }
}
