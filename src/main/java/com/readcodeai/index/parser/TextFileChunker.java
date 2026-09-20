package com.readcodeai.index.parser;

import com.readcodeai.index.model.CollectedChunk;
import com.readcodeai.index.model.FileOutcome;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 把**非 Java 的文本文件**（配置、SQL、文档、前端源码等）切成检索单元。
 *
 * <h3>为什么值得做，以及它**不是**"支持多语言"</h3>
 * 用户问「Redis 配置在哪」「这个接口的 SQL 怎么写的」时，答案在 {@code application.yml} 或 {@code schema.sql} 里 ——
 * 它们既不进符号表（没有方法/调用），也不该假装有。所以这里的定位写死：
 * <ul>
 *   <li><b>只做检索</b>：切成块进 {@code chunk} 表，全文检索能命中 → 问题是"能搜到"；</li>
 *   <li><b>不做解析</b>：没有符号、没有调用边，所以"谁调用了它"这类问题**不会**因为这些文件产生假答案；</li>
 *   <li><b>不进统计分母</b>：{@code parse_success_rate} 仍然只算 Java（文本文件不是"解析成功"，是被收录）。</li>
 * </ul>
 * 这样"宽"和"准"各归各的：Java 走深度解析，其它文本走检索，谁也不假装自己是对方。
 *
 * <h3>切法：按固定行数，而不是按符号</h3>
 * 配置/文档没有"符号"这个结构，硬找边界只会更糟。按 60 行一段切，
 * 内容**原样来自磁盘**（行号与内容都能回磁盘核对 —— 证据核验的前提）。
 */
public class TextFileChunker {

    /** 收录的后缀白名单：只说"能搜到配置、SQL、文档与前端源码"，不做"什么文件都收"。 */
    private static final Set<String> EXTENSIONS = Set.of(
            "xml", "yml", "yaml", "properties", "toml", "ini", "conf", "cfg",
            "sql", "md", "txt",
            "json", "html", "css", "js", "ts", "vue", "proto", "sh", "bat");

    /** 这些名字一看就是机器生成的巨物（锁文件、最小化产物），收了只会稀释检索。 */
    private static final Set<String> SKIP_NAMES = Set.of(
            "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "composer.lock", "poetry.lock");

    /** 一段多少行。60 行足够覆盖一个配置块或一屏文档，又不会把 chunk 撑得太大。 */
    private static final int LINES_PER_CHUNK = 60;

    /** 单文件行数上限：再长也切，但总行数太多说明它不是"配置或文档"，而是被误收的数据文件。 */
    private static final int MAX_LINES = 20_000;

    /** 一个文件里的文本块（{@code kind='TEXT'}）。 */
    public record TextChunks(FileOutcome file, List<CollectedChunk> chunks) {
    }

    /** 这些后缀要不要收（供收集文件时用，逻辑集中在这里，免得两处各写一份）。 */
    public static boolean isTextFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (SKIP_NAMES.contains(name)) {
            return false;
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 && EXTENSIONS.contains(name.substring(dot + 1));
    }

    /**
     * 切一个文件。
     *
     * @param relativePath 仓库内相对路径（与 Java 文件同一套写法）
     */
    public TextChunks chunk(Path file, String relativePath, int maxFileSizeKb) {
        long sizeKb;
        try {
            sizeKb = Files.size(file) / 1024;
        } catch (IOException e) {
            sizeKb = 0;
        }
        if (sizeKb > maxFileSizeKb) {
            return new TextChunks(new FileOutcome(relativePath, "", 0, false,
                    "文本文件超过 " + maxFileSizeKb + " KB，已跳过"), List.of());
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 非 UTF-8 的文本文件（少见但不为零）：如实记一条失败，不猜编码
            return new TextChunks(new FileOutcome(relativePath, "", 0, false,
                    "读取失败：" + e.getClass().getSimpleName() + "（可能是非 UTF-8 编码）"), List.of());
        }
        if (lines.size() > MAX_LINES) {
            return new TextChunks(new FileOutcome(relativePath, "", lines.size(), false,
                    "行数超过 " + MAX_LINES + "，已跳过（看着不像配置或文档）"), List.of());
        }

        List<CollectedChunk> chunks = new ArrayList<>();
        for (int start = 0; start < lines.size(); start += LINES_PER_CHUNK) {
            int end = Math.min(lines.size(), start + LINES_PER_CHUNK);
            String content = String.join("\n", lines.subList(start, end));
            if (content.isBlank()) {
                continue;
            }
            chunks.add(new CollectedChunk(relativePath, CollectedChunk.KIND_TEXT, null,
                    start + 1, end, sha256(content), content, Math.max(1, content.length() / 3)));
        }
        return new TextChunks(new FileOutcome(relativePath, sha256(String.join("\n", lines)), lines.size(), true, null),
                chunks);
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 必须提供 SHA-256", e);
        }
    }

    /** 收集目录下的文本文件（与应用排除规则的是同一批 glob）。 */
    public static List<Path> collect(Path root, List<java.nio.file.PathMatcher> excludes) {
        List<Path> found = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(TextFileChunker::isTextFile)
                    .filter(path -> excludes.stream().noneMatch(matcher ->
                            matcher.matches(Path.of(root.relativize(path).toString().replace('\\', '/')))))
                    .sorted()
                    .forEach(found::add);
        } catch (IOException e) {
            throw new UncheckedIOException("扫描文本文件失败", e);
        }
        return found;
    }
}
