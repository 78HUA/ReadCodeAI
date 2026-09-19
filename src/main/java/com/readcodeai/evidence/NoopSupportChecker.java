package com.readcodeai.evidence;

import com.readcodeai.agent.model.AskEvidence;
import com.readcodeai.agent.model.SupportCheck;

import java.nio.file.Path;
import java.util.List;

/**
 * 关闭 ③ 层时的实现：**返回"没做判定"，而不是"判定通过"**。
 *
 * <p>这个区分很要紧：把「没查」说成「查过没问题」是所有校验里最容易出的谎。
 * {@link SupportCheck.Status#NOT_CHECKED} 与 {@link SupportCheck.Status#SUPPORTED}
 * 是两个状态，界面与日志据此能说清"这次到底核验到哪一层"。
 */
public class NoopSupportChecker implements SupportChecker {

    private final String reason;

    public NoopSupportChecker(String reason) {
        this.reason = reason;
    }

    @Override
    public SupportCheck check(String question, String answer, List<AskEvidence> evidence, Path repoRoot) {
        return SupportCheck.notChecked(reason);
    }

    @Override
    public boolean rejectOnUnsupported() {
        return false;
    }

    @Override
    public String describe() {
        return "未启用（" + reason + "）：结论只过 ①② 层核验，不做「证据是否支持结论」的判定";
    }

    public String reason() {
        return reason;
    }
}
