package com.readcodeai.evidence;

import com.readcodeai.agent.model.AskEvidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 证据校验：**真读磁盘**，不信模型自己报的行号。
 *
 * <h3>为什么要分三层</h3>
 * 因为三层成本差两个数量级，而便宜的这两层就能拦住绝大多数编造：
 * <ul>
 *   <li><b>① 文件与行号有效性</b>：文件存在吗？行号在范围内吗？路径有没有越出仓库？
 *       —— 100% 程序化、零成本</li>
 *   <li><b>② 片段一致性</b>：模型引的那段代码，和磁盘上那几行对得上吗？
 *       —— 100% 程序化（真读文件），零成本</li>
 *   <li><b>③ 是否支持结论</b>：这段代码真的支持这个说法吗？
 *       —— 需要模型判或人工抽检，**成本高且有误判**，所以本阶段只留接口不做默认开启</li>
 * </ul>
 *
 * <h3>为什么必须真读文件</h3>
 * 索引建好之后源码可能改动、行号会漂移；而**模型给出的行号本来就不该被信任**。
 * 所以唯一的真相来源是**磁盘上的文件**：每次都重新读，不做缓存。
 */
@Component
public class EvidenceVerifier {

    private static final Logger log = LoggerFactory.getLogger(EvidenceVerifier.class);

    /** 片段太短就不做比对：三五个字符在哪儿都可能出现，比中了也说明不了什么。 */
    private static final int MIN_SNIPPET_LENGTH = 12;

    /**
     * 引用的行里至少这个比例能在磁盘区间里找到，才算"片段可信"。
     *
     * <p>为什么不是要求 100%：实测模型会把代码重排、合并、省略 —— 那是排版差异。
     * 而**引错位置**时比例会趋近于 0，所以这里能分开两种情形。
     * <p><b>诚实说明它的边界：这一层只能证明「引的代码大体在那个区间里」，
     * 不能证明「那段代码支持这个结论」—— 后者是第 ③ 层的事。</b>
     */
    private static final int MATCH_RATIO_PERCENT = 50;

    public enum FailureKind {
        OK,
        /** 引用了索引里没有的文件 */
        FILE_NOT_FOUND,
        /** 文件存在，但行号越界或区间不合法 */
        LINE_OUT_OF_RANGE,
        /** 行号有效，但模型引的片段与磁盘内容对不上 */
        CONTENT_MISMATCH,
        /** 路径越出仓库范围（正常不会发生，防的是模型给出 ../../ 这类路径） */
        PATH_ESCAPES_REPO
    }

    /** 一条证据的校验结果。{@code snippetMatches} 为 null 表示模型没给片段，这一项未核验。 */
    public record VerifiedEvidence(
            AskEvidence evidence,
            FailureKind failureKind,
            Boolean snippetMatches,
            int fileLineCount,
            String detail) {

        public boolean passed() {
            return failureKind == FailureKind.OK;
        }

        public boolean snippetUnverified() {
            return snippetMatches == null;
        }
    }

    public record Report(
            List<VerifiedEvidence> evidence,
            Map<FailureKind, Integer> failureCounts,
            int passed,
            int failed,
            int snippetUnverified) {

        /** ① 层全部通过 —— 这是"证据有效"的下限。 */
        public boolean allValid() {
            return failed == 0;
        }

        public int mismatchCount() {
            return failed;
        }
    }

    /** 逐条校验。**每一条都重新读文件**，不用索引里的任何缓存位置。 */
    public Report verify(Path repoRoot, List<AskEvidence> evidence) {
        Path root = repoRoot.toAbsolutePath().normalize();
        List<VerifiedEvidence> results = new ArrayList<>();
        Map<FailureKind, Integer> counts = new EnumMap<>(FailureKind.class);

        for (AskEvidence item : evidence) {
            VerifiedEvidence result = verifyOne(root, item);
            results.add(result);
            counts.merge(result.failureKind(), 1, Integer::sum);
        }

        int passed = (int) results.stream().filter(VerifiedEvidence::passed).count();
        int snippetUnverified = (int) results.stream().filter(VerifiedEvidence::snippetUnverified).count();
        Report report = new Report(results, counts, passed, results.size() - passed, snippetUnverified);
        if (report.failed() > 0) {
            log.warn("证据校验未通过 {} 条：{}", report.failed(), counts);
        }
        return report;
    }

    private VerifiedEvidence verifyOne(Path root, AskEvidence item) {
        if (item.file() == null || item.file().isBlank()) {
            return fail(item, FailureKind.FILE_NOT_FOUND, "证据里没有文件路径", 0);
        }
        Path file;
        try {
            file = root.resolve(item.file().replace('\\', '/')).normalize();
        } catch (InvalidPathException e) {
            // 模型给出的"文件"里有不合法的字符（实测：把行号一起写进了路径，如 "...Factory.java:144"）。
            // 那是**待核验的脏数据**，不是能让整次问答崩掉的错误 —— 核验层的职责就是把它挡住，
            // 它自己反倒抛异常就本末倒置了（这个 case 是第 5 步真实模型实验撞出来的）。
            return fail(item, FailureKind.FILE_NOT_FOUND, "文件路径不合法：" + item.file(), 0);
        }
        if (!file.startsWith(root)) {
            return fail(item, FailureKind.PATH_ESCAPES_REPO, "路径越出仓库范围：" + item.file(), 0);
        }
        if (!Files.isRegularFile(file)) {
            return fail(item, FailureKind.FILE_NOT_FOUND, "文件不存在：" + item.file(), 0);
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fail(item, FailureKind.FILE_NOT_FOUND,
                    "文件读不出来：" + e.getClass().getSimpleName(), 0);
        }

        if (item.startLine() < 1 || item.endLine() < item.startLine() || item.endLine() > lines.size()) {
            return fail(item, FailureKind.LINE_OUT_OF_RANGE,
                    "行号 %d-%d 超出文件范围（共 %d 行）".formatted(item.startLine(), item.endLine(), lines.size()),
                    lines.size());
        }

        String fromDisk = String.join("\n", lines.subList(item.startLine() - 1, item.endLine()));
        Boolean snippetMatches = matchSnippet(item.snippet(), fromDisk);
        if (Boolean.FALSE.equals(snippetMatches)) {
            // 把两边的原文打出来 —— 「为什么没匹配上」必须可诊断，否则只能靠猜
            log.info("片段未核验（引用与磁盘不一致）：{} · 引用前 80 字：{} · 磁盘前 80 字：{}",
                    item.location(), excerpt(item.snippet()), excerpt(fromDisk));
            return new VerifiedEvidence(item, FailureKind.CONTENT_MISMATCH, false, lines.size(),
                    "模型引用的片段与磁盘内容对不上");
        }
        return new VerifiedEvidence(item, FailureKind.OK, snippetMatches, lines.size(), null);
    }

    private static String excerpt(String text) {
        if (text == null) {
            return "(空)";
        }
        String normalized = normalize(text);
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80) + "...";
    }

    /**
     * 片段比对：**逐行匹配**，而不是整段连续比对。
     *
     * <p>为什么不是整段比对：实测发现模型经常**省略中间几行**（比如引用了注解和方法签名，
     * 却把中间的 javadoc 跳过了）—— 那是引用的常见形态，不是编造。
     * 要求整段连续会把这类真实引用误杀，于是好答案被拒。
     *
     * <p>为什么逐行匹配仍然拦得住编造：**被引用的每一行都必须在磁盘那几行里找得到**。
     * 实测拦下的正是最要紧的一类错误 —— 模型把真实代码**引到了错误的文件/行号上**
     * （例如说在 A 文件，实际那句话在 B 文件），这种逐行比对必然对不上。
     *
     * @return true / false；模型没给片段、或片段里没有足够长的行时返回 null（未核验，不算失败）
     */
    static Boolean matchSnippet(String snippet, String fromDisk) {
        if (snippet == null || snippet.isBlank()) {
            return null;
        }
        List<String> quotedLines = substantialLines(snippet);
        if (quotedLines.isEmpty()) {
            // 引用的全是很短的行（如单个大括号），没有判别力 —— 按未核验处理
            return null;
        }
        List<String> diskLines = normalizedLines(fromDisk);
        // 只允许一个方向：**磁盘上的某一行必须包含引用的这一行**。
        // 反过来（引用行包含磁盘行）太宽 —— 编造的长句里往往正好包含真实的短行，会被误判成通过。
        long matched = quotedLines.stream()
                .filter(quoted -> diskLines.stream().anyMatch(disk -> disk.contains(quoted)))
                .count();
        // 用比例而不是"全部命中"：实测模型会重排、合并、省略行 —— 那是排版差异，不是编造。
        // 而**引错位置**（引的代码根本不在这个区间）会几乎全部对不上，比例判据拦得住。
        boolean passes = matched * 100 >= quotedLines.size() * MATCH_RATIO_PERCENT;
        if (!passes) {
            log.info("片段匹配率过低：{}/{} 行在磁盘区间里找得到（阈值 {}%）",
                    matched, quotedLines.size(), MATCH_RATIO_PERCENT);
        }
        return passes;
    }

    /** 归一化后的非空行，只保留够长的（短行在哪儿都可能有，判不出什么）。 */
    private static List<String> substantialLines(String text) {
        return normalizedLines(text).stream()
                .filter(line -> line.length() >= MIN_SNIPPET_LENGTH)
                .map(EvidenceVerifier::stripLineNumberPrefix)
                .filter(line -> line.length() >= MIN_SNIPPET_LENGTH)
                .toList();
    }

    /**
     * 去掉模型抄进来的行号前缀（{@code 42: } 或 {@code 42| }）。
     *
     * <p><b>为什么必须容忍它</b>：工具读源码时给出的就是**带行号的原文**
     * （模型需要行号才知道该引用哪几行），它照抄时很容易把行号一起抄进 snippet。
     * 那是排版差异，不是编造 —— 不剥掉的话，一个完全正确的引用会被判成"内容对不上"。
     * 这个自相矛盾是**写第 5 步的测试时撞出来的**：工具给出的片段本身也带了行号，
     * 于是它自己的证据过不了自己家的核验。
     *
     * <p>剥掉行号不会削弱核验：行号是否有效由 ① 层单独管，
     * 这一层只管"引的代码文本是否真在磁盘上"。
     */
    static String stripLineNumberPrefix(String line) {
        return line.replaceFirst("^\\d{1,7}\\s*[:|]\\s*", "");
    }

    private static List<String> normalizedLines(String text) {
        return text.lines()
                .map(EvidenceVerifier::normalize)
                .filter(line -> !line.isEmpty())
                .toList();
    }

    private static String normalize(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    private static VerifiedEvidence fail(AskEvidence item, FailureKind kind, String detail, int fileLineCount) {
        return new VerifiedEvidence(item, kind, null, fileLineCount, detail);
    }
}
