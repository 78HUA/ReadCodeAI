package com.readcodeai.agent.model;

/** 答案是怎么来的。对使用者透明：**确定性路线连模型都不用**。 */
public enum AnsweredBy {
    /** 调用图 / 符号表直接算出来的，未经过模型 */
    STATIC,
    /** 由模型组织语言 */
    LLM,
    /** 没有答案（拒答或检索为空） */
    NONE
}
