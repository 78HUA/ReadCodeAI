package com.readcodeai.evidence;

import com.readcodeai.agent.model.AskEvidence;
import com.readcodeai.retrieve.model.ChunkHit;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 定向补检索：证据校验没过时，**按失败类型分别处理**，而不是笼统地"重试一次"。
 *
 * <p>关键判断：**我们知道刚才发给了模型哪些片段**。所以纠正的锚点是那些片段，而不是重新猜一遍 ——
 * 这就是"定向"的含义。模型把行号记错了、把路径写短了，都能拿真实片段纠回来；
 * 而**引的代码与磁盘完全对不上**这种（编造），纠不回来，只能丢掉并计数。
 *
 * <p>修好的候选会**再走一遍校验**（由调用方执行）—— 修正本身也可能失败，
 * 不能因为"我修过"就当作通过。
 */
@Component
public class EvidenceRepair {

    public record Result(List<AskEvidence> candidates, List<String> actions, int dropped) {

        public boolean changedAnything() {
            return !actions.isEmpty();
        }
    }

    /**
     * @param report     上一次校验的结果
     * @param sentChunks 本次实际发给模型的片段（纠正的锚点）
     */
    public Result repair(EvidenceVerifier.Report report, List<ChunkHit> sentChunks) {
        List<AskEvidence> candidates = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        int dropped = 0;

        for (EvidenceVerifier.VerifiedEvidence verified : report.evidence()) {
            AskEvidence item = verified.evidence();
            switch (verified.failureKind()) {
                case OK -> candidates.add(item);

                case LINE_OUT_OF_RANGE -> {
                    ChunkHit match = bySameFile(sentChunks, item.file());
                    if (match != null) {
                        candidates.add(item.withRange(match.startLine(), match.endLine()));
                        actions.add("行号越界 → 按发给模型的片段修正为 %s:%d-%d"
                                .formatted(match.filePath(), match.startLine(), match.endLine()));
                    } else {
                        dropped++;
                        actions.add("行号越界且找不到对应片段 → 丢弃 " + item.location());
                    }
                }

                case FILE_NOT_FOUND -> {
                    // 模型常把路径写短（只给文件名或去掉一层目录），用后缀匹配纠回来
                    ChunkHit match = byPathSuffix(sentChunks, item.file());
                    if (match != null) {
                        candidates.add(new AskEvidence(match.filePath(), match.startLine(), match.endLine(),
                                item.snippet(), item.why()));
                        actions.add("文件路径对不上 → 按发给模型的片段修正为 " + match.filePath());
                    } else {
                        dropped++;
                        actions.add("文件不存在 → 丢弃 " + item.file());
                    }
                }

                default -> {
                    // CONTENT_MISMATCH：引的代码在磁盘上找不到，这是编造的特征，纠正不了
                    dropped++;
                    actions.add("无法修正（" + verified.failureKind() + "）→ 丢弃 " + item.location());
                }
            }
        }
        return new Result(candidates, actions, dropped);
    }

    private static ChunkHit bySameFile(List<ChunkHit> chunks, String file) {
        String normalized = normalize(file);
        return chunks.stream()
                .filter(chunk -> normalize(chunk.filePath()).equals(normalized))
                .findFirst()
                .orElse(null);
    }

    private static ChunkHit byPathSuffix(List<ChunkHit> chunks, String file) {
        String normalized = normalize(file);
        return chunks.stream()
                .filter(chunk -> {
                    String candidate = normalize(chunk.filePath());
                    return candidate.endsWith(normalized) || normalized.endsWith(candidate);
                })
                .findFirst()
                .orElse(null);
    }

    private static String normalize(String path) {
        return path == null ? "" : path.replace('\\', '/').strip();
    }
}
