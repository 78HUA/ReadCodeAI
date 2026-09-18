package com.readcodeai.agent.model;

import java.util.List;

/** 证据校验的摘要。放在响应里，是为了让「这次到底核验了几条、有几条没过」对外可见。 */
public record VerificationSummary(int verified, int mismatch, List<String> repairs) {

    public static VerificationSummary none() {
        return new VerificationSummary(0, 0, List.of());
    }

    public boolean allVerified() {
        return mismatch == 0;
    }
}
