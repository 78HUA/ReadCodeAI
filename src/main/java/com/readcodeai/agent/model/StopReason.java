package com.readcodeai.agent.model;

/**
 * 一次问答**为什么停**。
 *
 * <p>这个枚举存在的意义是把「没答出来」拆开：模型说不够（拒答）、模型乱跳被环检测拦住、
 * 预算耗尽、输出格式错误、证据对不上磁盘 —— 每种都对应不同的改进动作。
 * 只报一句"失败了"，等于把可诊断的问题变成不可诊断的。
 */
public enum StopReason {

    /** 模型给出了带证据的结论 */
    FINAL("模型给出了结论"),
    /** 连续两轮重复同一次查询 —— 判定为绕圈，主动停 */
    NO_PROGRESS("模型连续重复已查过的调用，判定为绕圈"),
    BUDGET_ROUNDS("轮次预算耗尽"),
    BUDGET_DURATION("时长预算耗尽"),
    BUDGET_TOKENS("token 预算耗尽"),
    BUDGET_COST("成本预算耗尽"),
    /** 模型输出不是合法 JSON，提示后仍然如此 */
    FORMAT_ERROR("模型输出不是合法 JSON"),
    /** 模型给了结论，但所有证据都没通过磁盘核验 */
    EVIDENCE_REJECTED("结论的全部证据未通过核验"),
    /** 证据通过了 ①② 层，但 ③ 层判定它不支持结论（只在 support-check=reject 时会出现） */
    SUPPORT_REJECTED("证据不支持结论（③ 层判定）"),
    /** 模型给了结论却没有任何证据 —— 按验收标准不予返回 */
    NO_EVIDENCE("结论没有任何证据"),
    /** 模型自己说查不到 */
    REFUSED_BY_MODEL("模型判断现有材料不足以回答"),
    /** 模型调用失败（网络/接口异常）—— 与"没答案"是两回事，必须分开报 */
    LLM_CALL_FAILED("模型调用失败"),
    /** 没配模型，多跳不可用 */
    LLM_UNAVAILABLE("未配置模型，多跳检索不可用"),
    /** 单跳路径（没有进入多跳循环） */
    SINGLE_HOP("走的是单跳路径"),
    /** 确定性问题，静态分析直接算出 */
    STATIC("确定性问题，由静态分析直接算出"),
    /** 走了一圈但什么都没查到 */
    NOTHING_FOUND("轨迹里没有查到任何相关符号"),
    /** 总结类问题**没有走检索**，直接由结构化摘要作答（结构算出来、语义模型补） */
    SUMMARY_ANSWERED("总结类问题，走结构化摘要作答");

    private final String label;

    StopReason(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isBudget() {
        return this == BUDGET_ROUNDS || this == BUDGET_DURATION
                || this == BUDGET_TOKENS || this == BUDGET_COST;
    }
}
