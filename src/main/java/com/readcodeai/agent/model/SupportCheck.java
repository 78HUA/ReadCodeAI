package com.readcodeai.agent.model;

/**
 * ③ 层校验的结果：**这段代码真的支持这条结论吗**。
 *
 * <h3>为什么它是三层里唯一要额外花钱的一层</h3>
 * ①② 层是程序化比对（文件在不在、行号对不对、片段与磁盘一致不一致），成本为零、结论确定。
 * 但「证据真实」和「证据支持结论」是两件事 —— 实测撞到过最典型的一种：
 * 结论说的是**菜品**分页怎么实现，引的证据却是**订单**分页的代码，
 * 文件与行号全是真的、片段也和磁盘一致，①② 层一路放行。
 * 要发现这种错位，只能读懂"两段话是不是在说同一件事"，所以这一层必须调模型。
 *
 * <h3>因为调模型，所以它是标记而不是默认拒答</h3>
 * 模型判定会有误伤（可能把一条正确的结论判成不支持），所以默认行为是**把判定结果标出来**
 * （{@code readcodeai.verify.support-check=mark}），让使用者自己看；
 * 要严格拒答改成 {@code reject}。判定失败（模型坏了、输出不是 JSON）单独归为
 * {@link Status#UNAVAILABLE} —— **绝不能悄悄当成"通过"**，那等于把没做过的校验说成做过。
 *
 * @param status        判定结果
 * @param reason        一句话理由（{@code UNSUPPORTED} 时它必须点出对不上的是什么，否则无从复核）
 * @param promptTokens  这次判定自己花掉的 prompt token（**与生成答案的用量分开记**：
 *                      "这份答案当初花了多少"和"核验它花了多少"是两笔账，混在一起就都说不清了）
 * @param completionTokens 同上，completion 部分
 */
public record SupportCheck(Status status, String reason, int promptTokens, int completionTokens) {

    public enum Status {
        /** 没做判定：功能关闭、静态路线（答案由查询算出，没有"组织语言"这一步），或本次是拒答 */
        NOT_CHECKED,
        /** 判定：证据支持结论 */
        SUPPORTED,
        /** 判定：**证据不支持结论**（张冠李戴、结论比证据说得更多）—— 真正要显眼标出的状态 */
        UNSUPPORTED,
        /** 判定：材料不足以判断（模型说它判不了 —— 这比逼它猜一个答案好） */
        UNCERTAIN,
        /** 开了判定，但这次判定本身没成功（模型故障、输出不是 JSON）—— 不能算通过 */
        UNAVAILABLE
    }

    public static SupportCheck notChecked(String reason) {
        return new SupportCheck(Status.NOT_CHECKED, reason, 0, 0);
    }

    public static SupportCheck supported(String reason, int promptTokens, int completionTokens) {
        return new SupportCheck(Status.SUPPORTED, reason, promptTokens, completionTokens);
    }

    public static SupportCheck unsupported(String reason, int promptTokens, int completionTokens) {
        return new SupportCheck(Status.UNSUPPORTED, reason, promptTokens, completionTokens);
    }

    public static SupportCheck uncertain(String reason, int promptTokens, int completionTokens) {
        return new SupportCheck(Status.UNCERTAIN, reason, promptTokens, completionTokens);
    }

    public static SupportCheck unavailable(String reason) {
        return new SupportCheck(Status.UNAVAILABLE, reason, 0, 0);
    }

    /** 真的做出了判定（无论结论支持与否）。 */
    public boolean checked() {
        return status == Status.SUPPORTED || status == Status.UNSUPPORTED || status == Status.UNCERTAIN;
    }

    /** 需要显眼标出的那种结果。 */
    public boolean flagged() {
        return status == Status.UNSUPPORTED;
    }

    public int tokens() {
        return promptTokens + completionTokens;
    }

    /** 给界面与日志用的一句话。 */
    public String describe() {
        return switch (status) {
            case NOT_CHECKED -> "未做 ③ 层判定（" + reason + "）";
            case SUPPORTED -> "③ 层判定：证据支持结论";
            case UNSUPPORTED -> "③ 层判定：**证据不支持结论** —— " + reason;
            case UNCERTAIN -> "③ 层判定：材料不足以判断 —— " + reason;
            case UNAVAILABLE -> "③ 层判定未完成（" + reason + "）";
        };
    }
}
