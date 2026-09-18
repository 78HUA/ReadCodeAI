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
 */
public record VerificationSummary(int verified, int mismatch, List<String> repairs) {

    public static VerificationSummary none() {
        return new VerificationSummary(0, 0, List.of());
    }

    /** 首轮是否一次通过。**为 false 不等于答案里混了脏证据**（见 {@code mismatch} 的说明）。 */
    public boolean firstPassClean() {
        return mismatch == 0;
    }
}
