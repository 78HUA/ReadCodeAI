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

    /**
     * 项目级那一问：材料少、问题小，重点就一个。
     *
     * <p><b>为什么和"模块说明"分成两次调用</b>：实测一次让它同时干两件事时，这个免费模型会**丢掉其中一块**
     * （reggie 那次就没给 overview；而把同一件事单独问它时答得很好：
     * "一个外卖平台系统，用于管理餐厅菜品、订单、骑手等业务"）。拆开之后每次提示更短更聚焦，
     * 而且**一块失败不影响另一块** —— 项目一句话拿到了、模块说明没拿到，也照样能用。
     */
    private static final String PROJECT_PROMPT = """
            你是代码库理解助手。用户会给你一个 Java 仓库的**项目级材料**（业务对象、对外接口、核心类）。
            请回答：这个项目是做什么的。

            硬性要求：
            1. overview 一句话，不超过 60 个字，像跟同事介绍那样说清**业务领域**；
               **判断业务领域要看业务对象的名字**（菜品/订单/购物车这类），不要只看包名或类名后缀。
            2. features 给 3–5 条主要功能，每条不超过 30 个字。
            3. **不要写任何数字、行号、百分比**；尽量引用材料里的真实类名与接口路径，**不要编造**名字。
            4. 只输出 JSON，不要 Markdown 代码块，不要任何解释文字。

            输出格式：
            {"overview":"这个项目是做什么的","features":["主要功能一","主要功能二","主要功能三"]}
            """;

    /** 模块级那一问：每个模块大致负责什么。 */
    private static final String MODULES_PROMPT = """
            你是代码库理解助手。用户会给你一个 Java 仓库的**模块清单**（模块名、文件数、代表类）。
            请为每个模块写**一句话**说明它大致负责什么。

            硬性要求：
            1. 每个模块一句话，不超过 40 个字，用中文。
            2. **不要写任何数字、行号、百分比**。
            3. 尽量引用清单里的**真实类名**；**不要编造**不存在的名字。
            4. 只输出 JSON，不要 Markdown 代码块，不要解释文字。

            输出格式：
            {"notes":[{"module":"模块名","note":"一句话说明"}]}
            """;

    /** 只差一句话时的**定向重试**：问题缩到最小，不重复整个材料。 */
    private static final String OVERVIEW_RETRY_PROMPT = """
            上一次你没有给出 overview。这次**只**输出一个 JSON 对象，只含一个字段：
            {"overview":"这个项目是做什么的（不超过 60 个字，不写数字，不用编类名）"}
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

    public RepoSummary.Semantics describe(RepoView repo, RepoSummary.Structure structure,
                                         ProjectMaterialBuilder.Material material) {
        if (!llmClient.available()) {
            return RepoSummary.noSemantics("未配置 LLM（readcodeai.llm.*）：语义说明不可用；"
                    + "结构部分由索引算出，不受影响");
        }
        if (structure.modules().isEmpty() && material.isEmpty()) {
            return RepoSummary.noSemantics("这个仓库里既没有可归并的模块，也没有可说明的对象");
        }

        // ① 项目级：这个项目是做什么的（单独一问，见 PROJECT_PROMPT 的注释）
        ProjectAnswer project = askProject(repo, material);
        RepoSummary.ProjectNote overview = project.overview() == null ? null
                : verifyText(repo.id(), project.overview());
        List<RepoSummary.ProjectNote> features = project.features().stream()
                .map(text -> verifyText(repo.id(), text)).toList();

        // ② 模块级：每个模块大致负责什么（另一问；这一问失败也不影响上面那句）
        ModuleAnswer moduleAnswer = askModules(repo, structure);
        List<ModuleNote> verified = verify(repo.id(), moduleAnswer.notes());

        int promptTokens = project.promptTokens() + moduleAnswer.promptTokens();
        int completionTokens = project.completionTokens() + moduleAnswer.completionTokens();
        if (overview == null && verified.isEmpty()) {
            return RepoSummary.noSemantics("模型两块都没给出可用内容（项目一句话与模块说明都是空的）");
        }
        // token 用量必须记下来：摘要也是"花了多少"该能报出来的地方（之前这里漏了，补上）
        log.info("摘要语义：项目一句话={}（功能 {} 条）· 模块说明 {} 条（其中 {} 个含未核对的符号名）· "
                        + "prompt {} + completion {} token",
                overview != null, features.size(), verified.size(),
                verified.stream().filter(note -> !note.verified()).count(),
                promptTokens, completionTokens);
        return new RepoSummary.Semantics(true, llmClient.model(), null, overview, features, verified,
                false, java.time.LocalDateTime.now(), promptTokens, completionTokens);
    }

    private record ProjectAnswer(String overview, List<String> features,
                                 int promptTokens, int completionTokens) {
    }

    private record ModuleAnswer(List<RawNote> notes, int promptTokens, int completionTokens) {
    }

    /**
     * 问"这个项目是做什么的"；模型没给就**用最小提示再问一次**（定向重试，不是原样重发）。
     *
     * <p>实测这个免费模型在"答完一个问题就忘了另一个字段"这件事上很稳定 ——
     * 所以重试时把问题缩到只剩 overview 一个字段。
     */
    private ProjectAnswer askProject(RepoView repo, ProjectMaterialBuilder.Material material) {
        String userPrompt = "仓库：" + repo.name() + "（Java）\n\n项目级材料：\n" + material.describe()
                + "\n请回答：这个项目是做什么的。";
        int promptTokens = 0;
        int completionTokens = 0;
        for (int attempt = 0; attempt < 2; attempt++) {
            LlmClient.Completion completion;
            try {
                completion = llmClient.complete(PROJECT_PROMPT,
                        attempt == 0 ? userPrompt
                                : OVERVIEW_RETRY_PROMPT + "\n\n项目级材料：\n" + material.describe());
            } catch (RuntimeException e) {
                log.warn("项目级那一问调用失败：{}", e.toString());
                break;
            }
            promptTokens += completion.promptTokens();
            completionTokens += completion.completionTokens();
            try {
                ProjectPayload parsed = ModelJson.parse(completion.content(), ProjectPayload.class);
                if (parsed != null && parsed.overview() != null && !parsed.overview().isBlank()) {
                    return new ProjectAnswer(parsed.overview(),
                            parsed.features() == null ? List.of() : parsed.features(),
                            promptTokens, completionTokens);
                }
                log.info("模型没给出项目一句话（第 {} 次）：{}", attempt + 1,
                        attempt == 0 ? "用最小提示再问一次" : "放弃并如实返回空");
            } catch (ModelOutputFormatException e) {
                log.warn("项目级输出不是合法 JSON（第 {} 次）：{}", attempt + 1, e.getMessage());
            }
        }
        return new ProjectAnswer(null, List.of(), promptTokens, completionTokens);
    }

    private ModuleAnswer askModules(RepoView repo, RepoSummary.Structure structure) {
        if (structure.modules().isEmpty()) {
            return new ModuleAnswer(List.of(), 0, 0);
        }
        LlmClient.Completion completion;
        try {
            completion = llmClient.complete(MODULES_PROMPT, buildModulePrompt(repo, structure));
        } catch (RuntimeException e) {
            // 模块说明拿不到不算致命：项目一句话照样返回（部分结果好过整体失败）
            log.warn("模块说明那一问调用失败：{}", e.toString());
            return new ModuleAnswer(List.of(), 0, 0);
        }
        try {
            Notes notes = ModelJson.parse(completion.content(), Notes.class);
            return new ModuleAnswer(notes == null || notes.notes() == null ? List.of() : notes.notes(),
                    completion.promptTokens(), completion.completionTokens());
        } catch (ModelOutputFormatException e) {
            log.warn("模块说明不是合法 JSON：{}", e.getMessage());
            return new ModuleAnswer(List.of(), completion.promptTokens(), completion.completionTokens());
        }
    }

    private String buildModulePrompt(RepoView repo, RepoSummary.Structure structure) {
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

    /**
     * 核对模型写的**一句/一段话**：提到的名字是否能在索引里找到、有没有自说自话的数字。
     *
     * <p>与模块说明用的是同一套口径 —— 项目级的那句话同样不能编名字。
     */
    public RepoSummary.ProjectNote verifyText(long repoId, String text) {
        List<String> mentioned = new ArrayList<>();
        List<String> unverified = new ArrayList<>();
        Set<String> candidates = new LinkedHashSet<>();
        Matcher matcher = IDENTIFIER.matcher(text == null ? "" : text);
        while (matcher.find()) {
            String token = matcher.group();
            if (!STOP_WORDS.contains(token.toLowerCase(Locale.ROOT)) && !mentioned.contains(token)) {
                mentioned.add(token);
                candidates.add(token);
            }
        }
        Set<String> known = new LinkedHashSet<>(repository.exactNames(repoId, new ArrayList<>(candidates)));
        mentioned.stream().filter(token -> !known.contains(token)).forEach(unverified::add);
        List<String> numbers = new ArrayList<>();
        Matcher digits = DIGITS.matcher(text == null ? "" : text);
        while (digits.find()) {
            numbers.add(digits.group());
        }
        return new RepoSummary.ProjectNote(text, mentioned, unverified, numbers,
                unverified.isEmpty() && numbers.isEmpty());
    }

    /** 项目级那一问的返回。字段全用包装类型：小模型经常少给字段或给 null，不想为这个整条失败。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProjectPayload(String overview, List<String> features) {
    }

    /** 模块级那一问的返回（overview/features 留着只是为了让同一份解析器兼容旧格式）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Notes(String overview, List<String> features, List<RawNote> notes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RawNote(String module, String note) {
    }
}
