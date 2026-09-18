package com.readcodeai.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.readcodeai.agent.model.AskEvidence;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import tools.jackson.databind.ObjectMapper;

/**
 * 模型输出的 JSON 契约：**单跳问答与多跳循环共用一份定义**。
 *
 * <p>放在一个文件里不是偷懒，是因为「怎么把模型那点不规范的输出捞出来」这件事
 * 全项目只该有一份实现 —— 实测踩过的坑（Markdown 包裹、非法转义 {@code \(}、
 * {@code refused} 给成 null、把 {@code final} 写成裸 {@code answer}）必须一次修好、处处生效。
 *
 * <p>三层防线：
 * <ol>
 *   <li>剥壳：模型爱把 JSON 包在代码块里，或前后加一句话</li>
 *   <li>修补：把字符串里的**非法转义**修掉</li>
 *   <li>失败就明确抛 {@link ModelOutputFormatException} —— 不崩、也不假装答出来了</li>
 * </ol>
 * <p><b>绝不退回"把原文当答案"</b> —— 那等于绕过「必须带证据」这条验收标准。
 */
public final class ModelJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** {@code 文件:行号} 或 {@code 文件:起-止}（模型常把行号一起写进 file 字段）。 */
    private static final Pattern FILE_WITH_LINE = Pattern.compile("^(.+?):(\\d+)(?:-(\\d+))?$");

    private ModelJson() {
    }

    /** 模型给出的一条证据（原始形态，字段可能缺、可能是 null）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EvidenceEntry(String file, Integer startLine, Integer endLine,
                                String snippet, String why) {
    }

    /** 单跳问答的输出。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SingleHopAnswer(String answer, List<EvidenceEntry> evidence,
                                  Boolean refused, String refusalReason) {
    }

    /** 多跳循环里「给出结论」那一支。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FinalAnswer(String answer, List<EvidenceEntry> evidence,
                              Boolean refused, String refusalReason) {
    }

    /**
     * 多跳循环里模型的一轮输出：要么调工具，要么给结论。
     *
     * <p>顶层同时留着 {@code answer}/{@code evidence}：实测小模型会把结论**平铺**出来
     * （不套 {@code final} 那层壳）；同理工具调用也可能被套进 {@code action} 里。
     * 宽容解析，不为此丢掉一整轮。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Turn(
            String thought,
            String tool,
            Map<String, Object> args,
            @JsonProperty("action") Action action,
            @JsonProperty("final") FinalAnswer finalAnswer,
            String answer,
            List<EvidenceEntry> evidence,
            Boolean refused,
            String refusalReason) {

        public boolean hasFinal() {
            return finalAnswer != null || answer != null
                    || Boolean.TRUE.equals(refused)
                    || (evidence != null && !evidence.isEmpty());
        }

        public boolean hasAction() {
            return !hasFinal() && toolName() != null && !toolName().isBlank();
        }

        /** 工具名：顶层 {@code tool} 优先，其次 {@code action.tool}。 */
        public String toolName() {
            if (tool != null && !tool.isBlank()) {
                return tool;
            }
            return action == null ? null : action.tool();
        }

        public Map<String, Object> toolArgs() {
            if (args != null && !args.isEmpty()) {
                return args;
            }
            return action == null || action.args() == null ? Map.of() : action.args();
        }

        /** 给环检测用的参数键：值按出现顺序拼起来，够用且可解释。 */
        public String argsKey() {
            Map<String, Object> effective = toolArgs();
            if (effective.isEmpty()) {
                return "";
            }
            return effective.values().stream()
                    .map(value -> value == null ? "" : String.valueOf(value))
                    .collect(Collectors.joining(","));
        }

        public FinalAnswer effectiveFinal() {
            return finalAnswer != null ? finalAnswer
                    : new FinalAnswer(answer, evidence, refused, refusalReason);
        }
    }

    /** 工具调用被套在 {@code action} 里的写法：{@code {"action":{"tool":"...","args":{...}}}}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Action(String tool, Map<String, Object> args) {
    }

    public static <T> T parse(String raw, Class<T> type) throws ModelOutputFormatException {
        String object = extractObject(raw);
        try {
            return MAPPER.readValue(object, type);
        } catch (Exception first) {
            try {
                return MAPPER.readValue(sanitizeJson(object), type);
            } catch (Exception second) {
                throw new ModelOutputFormatException(
                        "JSON 不合法（修补后仍失败）：" + second.getMessage(), raw);
            }
        }
    }

    /** 剥掉 Markdown 包裹、取出最外层的 {@code {...}}。 */
    static String extractObject(String raw) {
        String text = stripFences(raw);
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new ModelOutputFormatException("模型没有返回 JSON", raw);
        }
        return text.substring(start, end + 1);
    }

    private static String stripFences(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                text = text.substring(firstNewline + 1, lastFence).strip();
            }
        }
        return text;
    }

    /**
     * 修掉 JSON 字符串里的非法转义：{@code \(} 这种模型写错的转义，去掉反斜杠、保留字符本身。
     * 合法的转义（双引号、反斜杠、斜杠、b、f、n、r、t，以及 unicode 形式）原样保留。
     *
     * <p>已知局限：引号配对用的是"遇到反斜杠外的引号就切换状态"这种简化判断，
     * 遇到字符串内的裸引号会判断错 —— 所以它只是**尽力而为的修补**，不是解析器。
     */
    static String sanitizeJson(String json) {
        StringBuilder out = new StringBuilder(json.length());
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && inString) {
                char next = i + 1 < json.length() ? json.charAt(i + 1) : 0;
                boolean legal = next == '"' || next == '\\' || next == '/' || next == 'b'
                        || next == 'f' || next == 'n' || next == 'r' || next == 't' || next == 'u';
                if (legal) {
                    out.append(c);
                    out.append(next);
                    i++;
                }
                // 非法转义：丢掉反斜杠（连 next 一起在下一轮原样追加）
                continue;
            }
            if (c == '"') {
                inString = !inString;
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * 丢掉缺文件或缺行号的证据条目 —— 这类条目无法核验，留着只会污染答案。
     *
     * <p><b>核心原则：解析宽容、校验严格。</b> 小模型常给 null 或缺字段，容错是为了不整条失败；
     * 但缺字段的证据必须被丢掉 —— 放出去就等于拿脏数据当证据。
     *
     * <p>其中一种"不规范的写法"实测撞到过，必须容忍：模型会把**行号写在 file 里**
     * （{@code "...Factory.java:144"}）。原因不难理解 —— 观察文本里到处是 {@code @ 文件:行号}
     * 这种写法，它照着抄了。不认这种写法的后果是**路径解析直接抛异常、整次问答崩掉**
     * （Windows 的路径里不允许 {@code :}）—— 一个格式差异不该有这么大的杀伤力。
     */
    public static List<AskEvidence> validEvidence(List<EvidenceEntry> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream()
                .map(ModelJson::splitLineFromFile)
                .filter(e -> e.file() != null && !e.file().isBlank())
                .filter(e -> e.startLine() != null && e.endLine() != null)
                .filter(e -> e.startLine() > 0 && e.endLine() >= e.startLine())
                .map(e -> new AskEvidence(e.file(), e.startLine(), e.endLine(),
                        e.snippet() == null ? "" : e.snippet(),
                        e.why() == null ? "" : e.why()))
                .toList();
    }

    /** {@code 文件:行号} / {@code 文件:起-止} 拆成文件与行号（路径里本来就带冒号时不会误判：冒号后必须全是数字）。 */
    static EvidenceEntry splitLineFromFile(EvidenceEntry raw) {
        if (raw == null || raw.file() == null) {
            return raw;
        }
        Matcher matcher = FILE_WITH_LINE.matcher(raw.file().strip());
        if (!matcher.matches()) {
            return raw;
        }
        Integer start = raw.startLine() != null ? raw.startLine() : Integer.valueOf(matcher.group(2));
        Integer end = raw.endLine() != null ? raw.endLine()
                : (matcher.group(3) != null ? Integer.valueOf(matcher.group(3)) : start);
        return new EvidenceEntry(matcher.group(1), start, end, raw.snippet(), raw.why());
    }

    /** 参数表（模型给的 args 是 JSON 对象，值可能是数字/布尔，统一转成字符串）。 */
    public static String arg(Map<String, Object> args, String key) {
        if (args == null) {
            return null;
        }
        Object value = args.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
