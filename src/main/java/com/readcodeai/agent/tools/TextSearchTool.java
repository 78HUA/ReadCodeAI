package com.readcodeai.agent.tools;

import com.readcodeai.agent.model.AskEvidence;
import com.readcodeai.retrieve.TextRetriever;
import com.readcodeai.retrieve.model.ChunkHit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 全文检索（第 2 层）：**不知道符号名时用它**。
 *
 * <p>多跳循环里的典型用法：模型不知道登记代码在哪个类里，先用关键词搜出线索，
 * 再用 {@code findDefinition} / {@code readSymbol} 把候选读实。
 * 所以它和符号工具的配合是"先撒网、再收口"。
 *
 * <p>命中块来自索引（ngram 全文索引），而**证据要走磁盘核验** ——
 * 索引建立之后源码若被改动，这里引用的片段会对不上，这是设计使然（宁可判失败也不放行旧内容）。
 */
public class TextSearchTool implements AgentTool {

    /** 一次取几段：多跳循环里它是线索工具，不需要一次给出二十段。 */
    private static final int LIMIT = 5;

    /** 每段喂给模型的最大字符数（上下文预算）。 */
    private static final int MAX_CONTENT_CHARS = 1600;

    /** 每段作为证据的最大行数。 */
    private static final int MAX_SNIPPET_LINES = 40;

    private final TextRetriever textRetriever;

    public TextSearchTool(TextRetriever textRetriever) {
        this.textRetriever = textRetriever;
    }

    @Override
    public String name() {
        return "textSearch";
    }

    @Override
    public String usage() {
        return "{\"query\":\"关键词，中英文都可以，多个词用空格分开\"}";
    }

    @Override
    public String description() {
        return "按关键词做全文检索（标识符与注释都能搜），返回命中的代码块位置与内容。";
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> args) {
        String query = AgentTool.arg(args, "query");
        if (query == null || query.isBlank()) {
            query = AgentTool.arg(args, "keyword");
        }
        if (query == null || query.isBlank()) {
            return AgentTool.missing("query", usage());
        }

        List<ChunkHit> hits = textRetriever.search(context.repoId(), query, LIMIT);
        if (hits.isEmpty()) {
            return ToolResult.miss("全文检索「" + query + "」没有命中任何代码块。换个词（用标识符或注释里的原词）再试。");
        }

        List<AskEvidence> evidence = new ArrayList<>();
        List<String> subjects = new ArrayList<>();
        StringBuilder observation = new StringBuilder("全文检索「" + query + "」命中 " + hits.size() + " 段：\n");
        for (ChunkHit hit : hits) {
            observation.append("\n--- ").append(hit.filePath())
                    .append(" lines=").append(hit.startLine()).append('-').append(hit.endLine());
            if (hit.symbolQualifiedName() != null) {
                observation.append(" symbol=").append(hit.symbolQualifiedName());
            }
            observation.append(" (").append(hit.kind()).append(")\n")
                    .append(clip(hit.content())).append("\n");
            if (hit.symbolQualifiedName() != null) {
                subjects.add(hit.symbolQualifiedName());
            }
            evidence.add(new AskEvidence(hit.filePath(), hit.startLine(), hit.endLine(),
                    snippet(hit.content()), "全文检索命中：" + query));
        }
        return ToolResult.of(observation.toString(), evidence, subjects);
    }

    private static String clip(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= MAX_CONTENT_CHARS
                ? content : content.substring(0, MAX_CONTENT_CHARS) + "\n…（截断）";
    }

    private static String snippet(String content) {
        if (content == null) {
            return "";
        }
        String[] lines = content.split("\n", -1);
        if (lines.length <= MAX_SNIPPET_LINES) {
            return content;
        }
        return String.join("\n", java.util.Arrays.copyOfRange(lines, 0, MAX_SNIPPET_LINES)) + "\n…（截断）";
    }
}
