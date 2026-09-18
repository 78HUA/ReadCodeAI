package com.readcodeai.agent.tools;

import com.readcodeai.agent.model.AskEvidence;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.SymbolView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 读出一个符号的真实源码（带行号）。
 *
 * <p><b>它是「逐字照抄」这件事能成立的原因</b>：结论要求证据里的 snippet 与源码逐字一致，
 * 模型没法凭记忆写出代码 —— 必须先把这个符号读出来，再从读到的文本里抄。
 *
 * <p>两点必须坚持：
 * <ul>
 *   <li><b>真读磁盘</b>，不读索引里存的副本 —— 源码改动后行号会漂移，
 *       只有磁盘是当前真相（这与证据校验同一条纪律）</li>
 *   <li><b>路径必须落在仓库内</b>：索引里的相对路径要还原成绝对路径，
 *       并检查没有越出仓库根（防的是脏数据里的 {@code ../../}）</li>
 * </ul>
 */
public class ReadSymbolTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(ReadSymbolTool.class);

    /** 一次最多读多少行：一个大类的全文会直接吃光上下文预算。 */
    private static final int MAX_LINES = 160;

    /** 一次最多读几个符号（重载多的时候别全读）。 */
    private static final int MAX_SYMBOLS = 3;

    /** 证据里的片段最长多少行 —— 核验器只要求"引的这几行能在磁盘区间里找到"，不需要全文。 */
    private static final int MAX_SNIPPET_LINES = 40;

    private final SymbolQueryService queries;

    public ReadSymbolTool(SymbolQueryService queries) {
        this.queries = queries;
    }

    @Override
    public String name() {
        return "readSymbol";
    }

    @Override
    public String usage() {
        return "{\"symbol\":\"类名.方法名 或 全限定名\"}";
    }

    @Override
    public String description() {
        return "读出符号的源码（带行号，最多 " + MAX_LINES
                + " 行）。要引用代码作为证据时必须先用它读出原文。";
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> args) {
        String symbol = AgentTool.arg(args, "symbol");
        if (symbol == null || symbol.isBlank()) {
            return AgentTool.missing("symbol", usage());
        }
        SymbolResolver.Resolution resolution = SymbolResolver.resolve(queries, context.repoId(), symbol);
        if (!resolution.found()) {
            return ToolResult.miss(resolution.note());
        }

        List<AskEvidence> evidence = new ArrayList<>();
        List<String> subjects = new ArrayList<>();
        StringBuilder observation = new StringBuilder();
        int read = 0;

        for (SymbolView target : resolution.symbols()) {
            if (read >= MAX_SYMBOLS) {
                observation.append("\n（同名符号还有 ")
                        .append(resolution.symbols().size() - read).append(" 个未读，需要时再指定限定名）");
                break;
            }
            Path file = resolveInsideRepo(context.repoRoot(), target.filePath());
            if (file == null) {
                observation.append("\n").append(target.qualifiedName())
                        .append("：索引里的路径 ").append(target.filePath())
                        .append(" 越出了仓库范围或文件不存在，未读取。");
                continue;
            }
            List<String> lines = readLines(file);
            if (lines == null) {
                observation.append("\n").append(target.qualifiedName())
                        .append("：索引里的文件 ").append(target.filePath())
                        .append(" 在磁盘上读不到（索引可能已过期）。");
                continue;
            }
            if (target.startLine() < 1 || target.startLine() > lines.size()) {
                observation.append("\n").append(target.qualifiedName())
                        .append("：索引里的起始行 ").append(target.startLine())
                        .append(" 已越界（磁盘上只有 ").append(lines.size())
                        .append(" 行）—— 源码改动过，索引需要重建。");
                continue;
            }

            int start = target.startLine();
            int end = Math.min(target.endLine(), Math.min(lines.size(), start + MAX_LINES - 1));
            String text = numbered(lines, start, end);
            observation.append("\n=== ").append(target.kind()).append(" ")
                    .append(target.qualifiedName()).append("  file=").append(target.filePath())
                    .append(" lines=").append(start).append("-").append(target.endLine());
            if (end < target.endLine()) {
                observation.append("（只读了前 ").append(end - start + 1).append(" 行）");
            }
            observation.append(" ===\n").append(text).append("\n");

            int snippetEnd = Math.min(end, start + MAX_SNIPPET_LINES - 1);
            // 证据里的片段是**不带行号的原文**：核验器要拿它跟磁盘逐行比对，
            // 带上 "12: " 这种前缀就成了另一种文本（观察里仍然带行号 —— 模型需要行号才能引用）
            evidence.add(new AskEvidence(target.filePath(), start, end,
                    raw(lines, start, snippetEnd),
                    "符号定义原文（从磁盘读出）"));
            subjects.add(target.qualifiedName());
            read++;
        }

        if (evidence.isEmpty()) {
            observation.insert(0, "没有读到任何源码：");
        }
        if (!resolution.note().isBlank()) {
            observation.append(resolution.note()).append("\n");
        }
        return ToolResult.of(observation.toString(), evidence, subjects);
    }

    /** 把索引里的相对路径还原成仓库内的绝对路径；越界、路径不合法或不存在都返回 null（**不抛异常**）。 */
    private static Path resolveInsideRepo(Path repoRoot, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        Path root = repoRoot.toAbsolutePath().normalize();
        Path file;
        try {
            file = root.resolve(filePath.replace('\\', '/')).normalize();
        } catch (java.nio.file.InvalidPathException e) {
            // 路径里带了非法字符（例如模型把行号写进了路径）—— 当作"读不到"处理，别让工具崩
            return null;
        }
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            return null;
        }
        return file;
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("读取源码失败：{} · {}", file, e.toString());
            return null;
        }
    }

    /** 带行号的原文 —— 行号是让模型能给出 startLine/endLine 的前提，不能省。 */
    private static String numbered(List<String> lines, int start, int end) {
        return java.util.stream.IntStream.rangeClosed(start, end)
                .mapToObj(line -> line + ": " + lines.get(line - 1))
                .collect(Collectors.joining("\n"));
    }

    private static String raw(List<String> lines, int start, int end) {
        return String.join("\n", lines.subList(start - 1, end));
    }
}
