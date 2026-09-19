package com.readcodeai.evidence;

import com.readcodeai.agent.model.AskEvidence;
import com.readcodeai.agent.model.SupportCheck;

import java.nio.file.Path;
import java.util.List;

/**
 * ③ 层校验：**这段代码真的支持这条结论吗**。
 *
 * <p>它的位置在 {@link EvidenceVerifier} 之后：那两层已经证明了"引用的文件与行号真实存在、
 * 片段与磁盘一致"，这里再往前走一步 —— 问"这段真实存在的代码，和这条结论说的是不是同一件事"。
 *
 * <p>顺序不能颠倒：拿一个可能根本没引对位置的证据去问"支持吗"，问出来的结果没有意义。
 *
 * <p><b>所以它是唯一会失败的一层</b>：①② 是程序化比对（要么过要么不过，结论确定），
 * 这里是模型判定（可能判错）。失败的处理方式因此也不同 —— 判定本身失败要如实记为
 * {@link SupportCheck.Status#UNAVAILABLE}，绝不静默当成通过。
 */
public interface SupportChecker {

    /**
     * @param repoRoot 仓库根目录：核验器要**自己去读**被引用的那几行真实代码，
     *                 而不是只看模型抄进 snippet 的副本 —— 判断的依据必须是磁盘上的原文
     */
    SupportCheck check(String question, String answer, List<AskEvidence> evidence, Path repoRoot);

    /** 判成"不支持"时要不要按拒答处理（由配置决定，见 {@code readcodeai.verify.support-check}）。 */
    boolean rejectOnUnsupported();

    /** 日志与诊断用的一句话，说明当前是哪一种核验器。 */
    String describe();
}
