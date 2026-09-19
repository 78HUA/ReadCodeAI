package com.readcodeai.evidence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.readcodeai.agent.ModelJson;
import com.readcodeai.agent.ModelOutputFormatException;
import com.readcodeai.agent.model.AskEvidence;
import com.readcodeai.agent.model.SupportCheck;
import com.readcodeai.config.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 用一次**窄口径**的模型调用做 ③ 层判定。
 *
 * <h3>为什么是窄口径</h3>
 * 它拿到的材料只有三样：问题、结论、被引用的代码（**从磁盘读的原文**，不是模型抄的副本）。
 * 刻意**不给检索上下文**：那会让它顺着模型的思路走 —— 判"这条结论有没有道理"是另一件事，
 * 这里要判的是"这几行代码能不能支撑这句话"，只给这几行才判得准。
 *
 * <h3>三值而不是二值</h3>
 * 逼一个只会二选一的判官表态，它会开始猜。允许多一个 {@code unknown}，
 * 它就能在材料不足时退出来 —— 那比一个掷硬币的 yes/no 有用得多。
 */
public class ModelSupportChecker implements SupportChecker {

    private static final Logger log = LoggerFactory.getLogger(ModelSupportChecker.class);

    private static final String SYSTEM_PROMPT = """
            你是代码库问答的**证据核验员**。给你一个问题、一条结论、以及结论引用的代码片段，
            你只回答一件事：**这些片段真的支持这条结论吗**。

            判据（按重要性排序）：
            1. 片段的内容要真的说明了结论里说的事。**"片段确实存在"不算通过** —— 那是别的环节的职责。
            2. 结论提到的类、方法、业务对象，必须与片段里的**是同一个**。
               哪怕片段真实、结论也真实，只要两者说的不是同一件事（张冠李戴），一律判 unsupported。
            3. 结论比片段说得更多、更绝对（片段里查不到的分支、特例、数字、范围），判 unsupported。
            4. 片段太短、或信息不足以判断时判 unknown —— **不要猜**。

            只输出 JSON，不要 Markdown 代码块，不要任何解释文字：
            {"verdict":"supported|unsupported|unknown","reason":"一句话；判 unsupported 时必须点出对不上的是什么"}
            """;

    /** 判定材料的上限：证据条数与每条的代码行数都要封顶，否则一次判定就能顶掉问答本身的成本。 */
    private static final int MAX_EVIDENCE = 6;
    private static final int MAX_LINES_PER_EVIDENCE = 40;
    private static final int MAX_CHARS_PER_EVIDENCE = 1500;

    private final LlmClient llmClient;
    private final boolean rejectOnUnsupported;

    public ModelSupportChecker(LlmClient llmClient, boolean rejectOnUnsupported) {
        this.llmClient = llmClient;
        this.rejectOnUnsupported = rejectOnUnsupported;
    }

    @Override
    public SupportCheck check(String question, String answer, List<AskEvidence> evidence, Path repoRoot) {
        if (answer == null || answer.isBlank()) {
            return SupportCheck.notChecked("没有结论可判定");
        }
        if (evidence.isEmpty()) {
            return SupportCheck.notChecked("没有证据可判定");
        }
        if (!llmClient.available()) {
            return SupportCheck.unavailable("未配置 LLM，③ 层判定做不了");
        }

        LlmClient.Completion completion;
        try {
            completion = llmClient.complete(SYSTEM_PROMPT, buildPrompt(question, answer, evidence, repoRoot));
        } catch (RuntimeException e) {
            // 判定失败**不是**"判成通过"：如实记为 UNAVAILABLE，让使用者和指标都看得见
            log.warn("③ 层判定调用失败：{}", e.toString());
            return SupportCheck.unavailable("判定调用失败（" + e.getClass().getSimpleName() + "）");
        }

        Verdict verdict;
        try {
            verdict = ModelJson.parse(completion.content(), Verdict.class);
        } catch (ModelOutputFormatException e) {
            log.warn("③ 层判定的输出不是合法 JSON：{} · 原文前 200 字：{}",
                    e.getMessage(), abbreviate(e.rawOutput()));
            return SupportCheck.unavailable("判定输出不是合法 JSON");
        }

        int in = completion.promptTokens();
        int out = completion.completionTokens();
        String reason = verdict.reason() == null || verdict.reason().isBlank()
                ? "（模型没有给理由）" : verdict.reason().strip();
        SupportCheck result = switch (verdict.verdict() == null ? "" : verdict.verdict().strip().toLowerCase()) {
            case "supported" -> SupportCheck.supported(reason, in, out);
            case "unsupported" -> SupportCheck.unsupported(reason, in, out);
            case "unknown" -> SupportCheck.uncertain(reason, in, out);
            // 模型给了一个没约定的词：**按"判定没完成"处理**，不能猜它想说什么
            default -> SupportCheck.unavailable("判定给出了无法识别的结论：" + excerpt(verdict.verdict()));
        };
        log.info("③ 层判定：{}（{} token · {} 字结论 · {} 条证据）",
                result.status(), result.tokens(), answer.length(), Math.min(evidence.size(), MAX_EVIDENCE));
        return result;
    }

    @Override
    public boolean rejectOnUnsupported() {
        return rejectOnUnsupported;
    }

    @Override
    public String describe() {
        return "模型判定（每答一次多一次模型调用）"
                + (rejectOnUnsupported ? " · 判成不支持即拒答" : " · 判成不支持只标记");
    }

    /**
     * 判定材料 = 问题 + 结论 + 每一条被引用的位置上的**磁盘原文**。
     *
     * <p>为什么读磁盘、而不是用模型给的 snippet：snippet 是模型抄的，判定要基于真实代码。
     * ② 层已经保证了 snippet 与磁盘一致，但这里多花的几次文件读取换来的是"判定依据无歧义"。
     * 位置读不出来（文件没了/行号越界）就不带代码，只留位置 —— 判官会因此判 unknown，那是正确的。
     */
    private String buildPrompt(String question, String answer, List<AskEvidence> evidence, Path repoRoot) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("问题：").append(question).append("\n\n结论：").append(answer).append("\n\n被引用的代码：\n");
        int index = 1;
        for (AskEvidence item : evidence.stream().limit(MAX_EVIDENCE).toList()) {
            prompt.append("\n[").append(index++).append("] ").append(item.location());
            if (item.why() != null && !item.why().isBlank()) {
                prompt.append("  引用它的理由：").append(item.why());
            }
            prompt.append('\n').append(readLines(repoRoot, item));
        }
        if (evidence.size() > MAX_EVIDENCE) {
            prompt.append("\n（另有 ").append(evidence.size() - MAX_EVIDENCE).append(" 条证据未列出）");
        }
        return prompt.toString();
    }

    /** 读被引用区间里的原文，带上行号（判官要能看到"第几行写了什么"）。 */
    static String readLines(Path repoRoot, AskEvidence evidence) {
        if (repoRoot == null || evidence.file() == null || evidence.file().isBlank()) {
            return "（位置无效，读不到代码）";
        }
        Path file;
        try {
            file = repoRoot.resolve(evidence.file().replace('\\', '/')).normalize();
        } catch (RuntimeException e) {
            return "（路径不合法，读不到代码）";
        }
        if (!file.startsWith(repoRoot.toAbsolutePath().normalize()) || !Files.isRegularFile(file)) {
            return "（文件不存在，读不到代码）";
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int from = Math.max(1, evidence.startLine());
            int to = Math.min(lines.size(), Math.min(evidence.endLine(), from + MAX_LINES_PER_EVIDENCE - 1));
            StringBuilder text = new StringBuilder();
            for (int line = from; line <= to && text.length() < MAX_CHARS_PER_EVIDENCE; line++) {
                text.append(line).append(": ").append(lines.get(line - 1)).append('\n');
            }
            return text.isEmpty() ? "（区间为空，读不到代码）" : text.toString();
        } catch (IOException e) {
            return "（文件读不出来：" + e.getClass().getSimpleName() + "）";
        }
    }

    /** 模型返回的判定 JSON。字段用包装类型：小模型经常少给字段，不想为这个整条失败。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Verdict(String verdict, String reason) {
    }

    private static String excerpt(String text) {
        if (text == null) {
            return "(空)";
        }
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= 40 ? oneLine : oneLine.substring(0, 40) + "...";
    }

    private static String abbreviate(String raw) {
        if (raw == null) {
            return "(空)";
        }
        return raw.length() <= 200 ? raw : raw.substring(0, 200) + "...";
    }
}
