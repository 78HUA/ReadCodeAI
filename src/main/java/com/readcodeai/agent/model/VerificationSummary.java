package com.readcodeai.agent.model;

import java.util.List;

/**
 * 证据校验的摘要。放在响应里，是为了让「这次到底核验了几条、有几条没过」对外可见。
 *
 * @param verified 最终采纳的证据条数（**每一条都通过了核验**）
 * @param mismatch **首轮**未通过的条数 —— 注意它是"修复前"的计数，
 *                 不是"返回的答案里有几条是脏的"：返回的证据永远只由通过核验的那些组成。
 *                 首轮不过、定向修正后通过的，会同时体现为 mismatch &gt; 0 与 repairs 非空。
 * @param repairs  定向修正做过哪些事（补检索、改行号……）
 * @param support  ③ 层判定（这段代码是否**支持**这条结论）。**与前三项是不同层的校验**：
 *                 前两项证明"证据真实存在"，这一项才碰"证据和结论是不是同一件事"。
 *                 没做判定时是 {@link SupportCheck#notChecked}，**不是"通过"**。
 */
public record VerificationSummary(int verified, int mismatch, List<String> repairs, SupportCheck support) {

    public static VerificationSummary none() {
        return new VerificationSummary(0, 0, List.of(),
                SupportCheck.notChecked("本次没有走证据核验这条路"));
    }

    /**
     * 走完 ①② 层、但**不做** ③ 层判定的场合（静态路线、拒答、判定关闭）。
     *
     * <p>给一个显式的工厂而不是让调用方随手传 null：③ 层"没做"这件事
     * 必须以 {@link SupportCheck.Status#NOT_CHECKED} 的形式留下来，不能被读成"做了且通过"。
     */
    public static VerificationSummary of(int verified, int mismatch, List<String> repairs, String whyNotChecked) {
        return new VerificationSummary(verified, mismatch, repairs, SupportCheck.notChecked(whyNotChecked));
    }

    /** 换掉 ③ 层判定，其余保持不变 —— 两条结论路径都只在这一处加东西。 */
    public VerificationSummary withSupport(SupportCheck check) {
        return new VerificationSummary(verified, mismatch, repairs, check);
    }

    /** 首轮是否一次通过。**为 false 不等于答案里混了脏证据**（见 {@code mismatch} 的说明）。 */
    public boolean firstPassClean() {
        return mismatch == 0;
    }
}
