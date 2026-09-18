package com.readcodeai.eval;

import com.readcodeai.index.store.SymbolQueryRepository;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.CallSiteView;
import com.readcodeai.retrieve.model.SymbolView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 链式问题生成器：**给"多跳到底有没有用"造一批有客观答案的题**。
 *
 * <p>题型：「哪些方法（直接或间接，最多 N 跳）最终会调用 X？」
 *
 * <p>标准答案仍然是**静态分析算出来的** —— 沿调用图做反向 BFS，取 N 跳内的全部祖先方法。
 * 这一点很关键：如果真值要靠人标或靠模型判，"多跳更完整"就成了一句无法证伪的话。
 *
 * <p><b>只出「链路比直接调用者更多」的题</b>：如果某个方法的全部祖先就是它的直接调用者，
 * 单跳与多跳的答案必然相同，拿这种题对比是没有意义的（会稀释结论）。
 */
@Service
public class ChainQuestionGenerator {

    private static final Logger log = LoggerFactory.getLogger(ChainQuestionGenerator.class);

    /** 默认追问深度：3 跳意味着"间接调用者的间接调用者"。 */
    public static final int DEFAULT_DEPTH = 3;

    /** 种子池大小：从"被调用最多"的方法里挑，保证调用图那一侧确有内容。 */
    private static final int SEED_POOL = 200;

    private final SymbolQueryService queries;
    private final SymbolQueryRepository repository;

    public ChainQuestionGenerator(SymbolQueryService queries, SymbolQueryRepository repository) {
        this.queries = queries;
        this.repository = repository;
    }

    /**
     * @param questionText   题面（含跳数上限 —— 让模型的停止条件有依据，也让真值口径与之对齐）
     * @param truth          标准答案：N 跳内的全部祖先方法的限定名
     * @param directCallers  直接调用者（**单跳基线的答案集合**）
     */
    public record ChainQuestion(String questionText, SymbolView target, Set<String> truth,
                                Set<String> directCallers, int depth) {

        public int truthSize() {
            return truth.size();
        }

        /** 链路比直接调用者多出多少 —— 这个差值就是多跳理论上的收益空间。 */
        public int chainOnly() {
            Set<String> extra = new LinkedHashSet<>(truth);
            extra.removeAll(directCallers);
            return extra.size();
        }
    }

    public List<ChainQuestion> generate(long repoId, long seed, int count, int depth) {
        List<SymbolView> pool = repository.mostCalledMethods(repoId, SEED_POOL);
        List<SymbolView> ordered = new ArrayList<>(pool);
        java.util.Collections.shuffle(ordered, new Random(seed));

        List<ChainQuestion> questions = new ArrayList<>();
        for (SymbolView target : ordered) {
            Set<String> truth = transitiveCallers(target.id(), depth);
            Set<String> direct = directCallers(target.id());
            if (truth.size() <= direct.size()) {
                continue;
            }
            questions.add(new ChainQuestion(questionText(target, depth), target, truth, direct, depth));
            if (questions.size() >= count) {
                break;
            }
        }
        log.info("链式问题：{} 道（depth={}, seed={}）", questions.size(), depth, seed);
        return questions;
    }

    /**
     * 沿调用图做反向 BFS：{@code depth} 跳内能到达目标的方法（不含目标自己，环会被跳过）。
     *
     * <p>返回的是**顺序**（近的在前）—— 理想模型会按这个顺序逐跳查询，
     * 所以它同时也是「离线脚本该念什么台词」的依据；{@link #transitiveCallers} 是同一个集合的无序视图，
     * 两者由同一段遍历产生，**保证口径一致**（真值和理想轨迹不可能对不上）。
     */
    public List<String> bfsPlan(long symbolId, int depth) {
        List<String> result = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        visited.add(symbolId);
        List<Long> frontier = List.of(symbolId);
        for (int level = 0; level < depth && !frontier.isEmpty(); level++) {
            List<Long> next = new ArrayList<>();
            for (Long id : frontier) {
                for (CallSiteView call : queries.callers(id)) {
                    if (call.symbolId() == null || !visited.add(call.symbolId())) {
                        continue;
                    }
                    result.add(call.symbolQualifiedName());
                    next.add(call.symbolId());
                }
            }
            frontier = next;
        }
        return result;
    }

    public Set<String> transitiveCallers(long symbolId, int depth) {
        return new LinkedHashSet<>(bfsPlan(symbolId, depth));
    }

    /**
     * 「谁的哪一行调进了链路」：调用点位置（{@code 文件:行}）→ 调用者限定名。
     *
     * <p>它是**对比实验的量尺**：两种跑法的证据粒度不同（单跳给的是检索到的代码块，
     * 多跳给的是调用点），只有把两边都落到"调用点位置"这个共同口径上，
     * 召回率才是可比的 —— 否则比出来的差异里混着格式差异，说明不了问题。
     */
    public Map<String, Set<String>> chainCallSites(long symbolId, int depth) {
        Map<String, Set<String>> byLocation = new LinkedHashMap<>();
        Set<Long> visited = new HashSet<>();
        visited.add(symbolId);
        List<Long> frontier = List.of(symbolId);
        for (int level = 0; level < depth && !frontier.isEmpty(); level++) {
            List<Long> next = new ArrayList<>();
            for (Long id : frontier) {
                for (CallSiteView call : queries.callers(id)) {
                    if (call.symbolId() == null || !visited.add(call.symbolId())) {
                        continue;
                    }
                    byLocation.computeIfAbsent(key(call.callSiteFile(), call.callLine()),
                                    ignored -> new LinkedHashSet<>())
                            .add(call.symbolQualifiedName());
                    next.add(call.symbolId());
                }
            }
            frontier = next;
        }
        return byLocation;
    }

    /** 证据位置键：{@code 文件:起始行} —— 全项目一种格式（与自动判卷的口径一致）。 */
    public static String key(String file, int line) {
        return file + ":" + line;
    }

    public Set<String> directCallers(long symbolId) {
        Set<String> direct = new LinkedHashSet<>();
        for (CallSiteView call : queries.callers(symbolId)) {
            if (call.symbolId() != null) {
                direct.add(call.symbolQualifiedName());
            }
        }
        return direct;
    }

    private static String questionText(SymbolView target, int depth) {
        return "沿调用链向上追：有哪些方法（直接或间接，最多 " + depth + " 跳）最终会调用 "
                + target.qualifiedName() + "？";
    }
}
