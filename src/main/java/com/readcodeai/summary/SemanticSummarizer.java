package com.readcodeai.summary;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.readcodeai.agent.ModelJson;
import com.readcodeai.agent.ModelOutputFormatException;
import com.readcodeai.config.LlmClient;
import com.readcodeai.retrieve.model.RepoView;
import com.readcodeai.summary.model.RepoSummary;
import com.readcodeai.summary.model.RepoSummary.Module;
import com.readcodeai.summary.model.RepoSummary.ModuleNote;
import com.readcodeai.summary.model.RepoSummary.SymbolRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 摘要里**唯一由模型生成**的那一小块：每个模块"大致负责什么"。
 *
 * <p>三条约束，缺一条这事就跑偏：
 * <ol>
 *   <li><b>只写一句话，不写数字</b>：数字归结构部分（查出来的），模型重复一遍只会引入不可核验的版本</li>
 *   <li><b>只能用给定的类名</b>：prompt 里把模块的代表类列出来，让它有据可依</li>
 *   <li><b>它写出来的每个符号名都要回索引核对</b>：找不到的单独标出来，
 *       展示时明确区分「核实过的」与「模型提到但索引里没有的」</li>
 * </ol>
 *
 * <p>没配模型时这里返回 {@code available=false}，**结构部分照常完整返回** ——
 * 与单跳问答、多跳检索同一套降级逻辑：剥掉模型，工具仍然是个能用的工具。
 */
@Component
public class SemanticSummarizer {

    private static final Logger log = LoggerFactory.getLogger(SemanticSummarizer.class);

    private static final String SYSTEM_PROMPT = """
            你是代码库理解助手。用户会给你一个 Java 仓库的**模块清单**（模块名、文件数、行数、代表类）。
            请为每个模块写**一句话**说明它大致负责什么。

            硬性要求：
            1. 每个模块只写一句话，不超过 40 个字，用中文。
            2. **不要写任何数字、行号、百分比** —— 数字由系统给出，你不需要重复。
            3. 说明里尽量引用清单里给出的**真实类名**；**不要编造**不存在的类名或方法名。
            4. 只输出 JSON，不要 Markdown 代码块，不要任何解释文字。

            输出格式：
            {"notes":[{"module":"模块名","note":"一句话说明"}]}
            """;

    /** 从模型写的中文句子里挑出候选符号名（英文标识符），用于回索引核对。 */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]{2,}");

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /** 这些词是句子里的英文虚词/常见词，不是符号名，别拿去核对（否则每句都会"未核对"一堆）。 */
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "this", "that", "its", "use", "used", "using", "code",
            "class", "classes", "method", "methods", "java", "json", "api", "util", "utils",
            "module", "modules", "note", "notes", "package", "packages");

    private final LlmClient llmClient;
    private final SummaryRepository repository;

    public SemanticSummarizer(LlmClient llmClient, SummaryRepository repository) {
        this.llmClient = llmClient;
        this.repository = repository;
    }

    /** 模型能不能用（结构部分不依赖它，但语义那一段依赖）。 */
    public boolean available() {
        return llmClient.available();
    }

    /** 缓存键要用模型名：换模型等于换一份生成结果。 */
    public String model() {
        return llmClient.model();
    }

    public RepoSummary.Semantics describe(RepoView repo, RepoSummary.Structure structure) {
        if (!llmClient.available()) {
            return RepoSummary.noSemantics("未配置 LLM（readcodeai.llm.*）：语义说明不可用；"
                    + "结构部分由索引算出，不受影响");
        }
        if (structure.modules().isEmpty()) {
            return RepoSummary.noSemantics("这个仓库里没有可归并的模块，没有可说明的对象");
        }

        LlmClient.Completion completion;
        try {
            completion = llmClient.complete(SYSTEM_PROMPT, buildPrompt(repo, structure));
        } catch (RuntimeException e) {
            // 模型故障不该让摘要整体失败：结构部分已经算好了，照常给它
            log.warn("摘要的语义说明调用失败：{}", e.toString());
            return RepoSummary.noSemantics("模型调用失败（" + e.getClass().getSimpleName()
                    + "）：结构部分不受影响");
        }

        Notes notes;
        try {
            notes = ModelJson.parse(completion.content(), Notes.class);
        } catch (ModelOutputFormatException e) {
            log.warn("摘要的语义说明不是合法 JSON：{}", e.getMessage());
            return RepoSummary.noSemantics("模型输出不是合法 JSON，语义说明不可用：" + e.getMessage());
        }
        if (notes == null || notes.notes() == null || notes.notes().isEmpty()) {
            return RepoSummary.noSemantics("模型没有给出任何模块说明");
        }

        List<ModuleNote> verified = verify(repo.id(), notes.notes());
        // token 用量必须记下来：摘要也是"花了多少"该能报出来的地方（之前这里漏了，补上）
        log.info("摘要语义：{} 个模块有说明 · 其中 {} 个含未核对的符号名 · prompt {} + completion {} token",
                verified.size(), verified.stream().filter(note -> !note.verified()).count(),
                completion.promptTokens(), completion.completionTokens());
        return new RepoSummary.Semantics(true, llmClient.model(), null, verified,
                false, java.time.LocalDateTime.now(),
                completion.promptTokens(), completion.completionTokens());
    }

    private String buildPrompt(RepoView repo, RepoSummary.Structure structure) {
        StringBuilder prompt = new StringBuilder("仓库：").append(repo.name())
                .append("（Java，共 ").append(repo.fileCount()).append(" 个文件 / ")
                .append(structure.modules().size()).append(" 个模块）\n\n模块清单：\n");
        for (Module module : structure.modules()) {
            prompt.append("- ").append(module.name())
                    .append("  文件 ").append(module.fileCount())
                    .append(" · 行 ").append(module.totalLoc())
                    .append(" · 代表类：")
                    .append(module.keyTypes().stream().map(SymbolRef::qualifiedName).limit(6)
                            .collect(Collectors.joining("、")))
                    .append('\n');
        }
        prompt.append("\n请为每个模块写一句话说明，按上面的 JSON 格式输出。");
        return prompt.toString();
    }

    /**
     * 核对：把模型写出来的候选符号名拿去索引里精确查，**查不到的原样标出来**。
     *
     * <p>为什么值得单独做这一步：模型写"这个模块负责 GsonBuilder 的配置"听起来毫无破绽，
     * 但如果索引里根本没有 {@code GsonBuilder}，那就是编的 ——
     * 而这在纯文本摘要里**肉眼看不出来**，只有回索引查一遍才知道。
     */
    public List<ModuleNote> verify(long repoId, List<RawNote> raw) {
        Set<String> candidates = new LinkedHashSet<>();
        for (RawNote note : raw) {
            if (note.note() == null) {
                continue;
            }
            Matcher matcher = IDENTIFIER.matcher(note.note());
            while (matcher.find()) {
                String token = matcher.group();
                if (!STOP_WORDS.contains(token.toLowerCase(Locale.ROOT))) {
                    candidates.add(token);
                }
            }
        }
        Set<String> known = new LinkedHashSet<>(repository.exactNames(repoId, new ArrayList<>(candidates)));

        List<ModuleNote> result = new ArrayList<>();
        for (RawNote note : raw) {
            if (note.module() == null || note.note() == null) {
                continue;
            }
            List<String> mentioned = new ArrayList<>();
            List<String> unverified = new ArrayList<>();
            Matcher matcher = IDENTIFIER.matcher(note.note());
            while (matcher.find()) {
                String token = matcher.group();
                if (STOP_WORDS.contains(token.toLowerCase(Locale.ROOT)) || mentioned.contains(token)) {
                    continue;
                }
                mentioned.add(token);
                if (!known.contains(token)) {
                    unverified.add(token);
                }
            }
            List<String> numbers = new ArrayList<>();
            Matcher digits = DIGITS.matcher(note.note());
            while (digits.find()) {
                numbers.add(digits.group());
            }
            result.add(new ModuleNote(note.module(), note.note(), mentioned, unverified, numbers,
                    unverified.isEmpty() && numbers.isEmpty()));
        }
        return result;
    }

    /** 模型返回的 JSON。字段全用包装类型：小模型经常少给字段或给 null，不想为这个整条失败。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Notes(List<RawNote> notes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RawNote(String module, String note) {
    }
}
