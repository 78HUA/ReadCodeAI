package com.readcodeai.retrieve;

import com.readcodeai.retrieve.model.ChunkHit;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 上下文选片：决定「检索回来的一堆块，哪些真正喂给模型」。
 *
 * <p>这一步不是"能塞多少塞多少"。把仓库一股脑塞进上下文有三个具体坏处：
 * ① 贵（token 是要花钱的）；② 慢；③ **中间塞满噪声时，模型更容易抓错重点**。
 *
 * <p>四条规则，每条都有明确理由：
 * <ol>
 *   <li><b>精确名提升</b>：问题里出现的标识符与某个块的符号名精确相同 → 排到最前。
 *       这是**确定性**的加分，不是猜的 —— 「谁调用了 submit」里的 submit 就该优先。</li>
 *   <li><b>去重</b>：同一位置、或内容完全相同的块只留一个（同一段代码被两种方式检索到是常事）。</li>
 *   <li><b>单文件上限</b>：一个文件最多贡献 N 个块。否则一个到处引用的热门文件会霸占整个上下文，
 *       把真正的答案挤出预算之外。</li>
 *   <li><b>预算截断</b>：按 token 估算累加，到顶就停；单个超大块（超过预算一半）直接跳过 ——
 *       它会把别的线索全挤掉，而它自己多半也不是想要的答案。</li>
 * </ol>
 *
 * <p>被丢掉的块都会记下原因，方便回答"为什么没找到"时区分**检索没召回**和**被预算挤掉了**。
 */
@Component
public class ContextSelector {

    /** 单个块超过预算这个比例就跳过：它会把其他线索全挤掉。 */
    private static final double OVERSIZED_CHUNK_RATIO = 0.5;

    public record Selection(List<ChunkHit> chunks, int totalTokens, List<String> droppedReasons) {

        public int size() {
            return chunks.size();
        }

        public boolean isEmpty() {
            return chunks.isEmpty();
        }
    }

    public Selection select(String question, List<ChunkHit> hits, long tokenBudget, int maxChunks,
                            int maxChunksPerFile) {
        List<String> dropped = new ArrayList<>();
        if (hits.isEmpty()) {
            return new Selection(List.of(), 0, dropped);
        }

        List<ChunkHit> ordered = promoteExactNameMatches(question, hits);

        List<ChunkHit> selected = new ArrayList<>();
        Set<String> seenPositions = new HashSet<>();
        Set<String> seenContents = new HashSet<>();
        Map<String, Integer> perFile = new HashMap<>();
        long used = 0;
        long oversizedThreshold = (long) (tokenBudget * OVERSIZED_CHUNK_RATIO);

        for (ChunkHit hit : ordered) {
            if (selected.size() >= maxChunks) {
                dropped.add("已达块数上限 " + maxChunks + "：" + hit.location());
                continue;
            }
            String position = hit.filePath() + ":" + hit.startLine() + "-" + hit.endLine();
            if (!seenPositions.add(position)) {
                dropped.add("同一位置重复：" + position);
                continue;
            }
            if (!seenContents.add(hit.content())) {
                dropped.add("内容完全相同（另一处重复代码）：" + position);
                continue;
            }
            int fileCount = perFile.getOrDefault(hit.filePath(), 0);
            if (fileCount >= maxChunksPerFile) {
                dropped.add("同文件块数已达 " + maxChunksPerFile + "：" + position);
                continue;
            }
            if (hit.tokenEstimate() > oversizedThreshold && !selected.isEmpty()) {
                dropped.add("单块过大（" + hit.tokenEstimate() + " tokens）：" + position);
                continue;
            }
            if (used + hit.tokenEstimate() > tokenBudget && !selected.isEmpty()) {
                dropped.add("超出 token 预算：" + position);
                continue;
            }
            selected.add(hit);
            perFile.merge(hit.filePath(), 1, Integer::sum);
            used += hit.tokenEstimate();
        }

        return new Selection(selected, (int) used, dropped);
    }

    /**
     * 把「符号名与问题里的标识符精确相同」的块提到最前。
     *
     * <p>保持稳定：同类之间仍按检索相关度排序，不重排其余部分。
     */
    private List<ChunkHit> promoteExactNameMatches(String question, List<ChunkHit> hits) {
        Set<String> candidates = new LinkedHashSet<>(QueryRouter.identifierCandidates(question));
        if (candidates.isEmpty()) {
            return hits;
        }
        List<ChunkHit> promoted = new ArrayList<>();
        List<ChunkHit> rest = new ArrayList<>();
        for (ChunkHit hit : hits) {
            if (candidates.contains(simpleNameOf(hit.symbolQualifiedName()))) {
                promoted.add(hit);
            } else {
                rest.add(hit);
            }
        }
        promoted.addAll(rest);
        return promoted;
    }

    /** 从 {@code com.x.Foo#bar/1} 或 {@code com.x.Foo} 里取出简单名。 */
    static String simpleNameOf(String symbolQualifiedName) {
        if (symbolQualifiedName == null) {
            return null;
        }
        String name = symbolQualifiedName;
        int hash = name.indexOf('#');
        if (hash >= 0) {
            name = name.substring(hash + 1);
            int slash = name.indexOf('/');
            return slash >= 0 ? name.substring(0, slash) : name;
        }
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }
}
