package com.readcodeai.index;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 解压 zip：**两个来源共用一份实现**（GitHub 源码包、用户上传的压缩包）。
 *
 * <p>安全约束（别人给的压缩包不能当命令执行）：
 * <ul>
 *   <li><b>路径校验</b>：每个条目解析后的绝对路径必须仍在目标目录内 —— 否则就是 zip slip，
 *       一个 {@code ../../} 条目就能往仓库外面写文件</li>
 *   <li><b>解压体积上限</b>：防 zip 炸弹（压缩比可以做到 1000:1，几 MB 能炸出几 GB）</li>
 * </ul>
 *
 * <p><b>为什么要抽出来</b>：这段逻辑原本长在 {@link RepoFetcher} 里，而上传入口需要一模一样的两条约束。
 * 复制一份的下场是"改了一处忘了另一处"，而这里忘记改的代价是一个安全漏洞。
 */
final class ZipExtractor {

    /** 解压后总大小上限 2 GB。 */
    static final long MAX_EXTRACTED_BYTES = 2L * 1024 * 1024 * 1024;

    private ZipExtractor() {
    }

    /**
     * @param stripTopLevelDirectory GitHub 的源码包外面裹着一层 {@code repo-HEAD/}；
     *                               上传的压缩包常常也是"整个项目文件夹压进去"。
     *                               为 true 时剥掉最外层目录（条目里根本没有目录时不做任何事）
     */
    static void extract(Path archive, Path destination, boolean stripTopLevelDirectory) throws IOException {
        Files.createDirectories(destination);
        long totalBytes = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (stripTopLevelDirectory) {
                    name = stripTopLevel(name);
                }
                if (name == null || name.isBlank()) {
                    continue;
                }
                Path target = destination.resolve(name).normalize();
                if (!target.startsWith(destination)) {
                    throw new IOException("压缩包里的路径越界（zip slip）：" + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                long written = Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                totalBytes += written;
                if (totalBytes > MAX_EXTRACTED_BYTES) {
                    throw new IOException("解压后体积超过上限，可能是 zip 炸弹，已中止");
                }
            }
        }
    }

    /** 去掉第一层目录（{@code gson-HEAD/src/...} → {@code src/...}）。 */
    static String stripTopLevel(String entryName) {
        int slash = entryName.indexOf('/');
        if (slash < 0) {
            return null;
        }
        return entryName.substring(slash + 1);
    }

    /** 清空目录（重新上传同一个项目时，不能把上一次留下的文件混进新索引）。 */
    static void clearDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            Files.createDirectories(directory);
            return;
        }
        try (var walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(directory)) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    /**
     * 压缩包里是不是只有一层目录。
     *
     * <p>只有一层时才剥 —— 剥错了会把项目的真实结构弄丢（例如把 {@code src} 当成包装目录扔掉，
     * 结果一个 Java 文件都找不到，用户只会看到"索引出 0 个文件"）。
     */
    static boolean hasSingleTopLevelDirectory(Path archive) throws IOException {
        String firstTop = null;
        boolean hasRootFile = false;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                int slash = name.indexOf('/');
                if (slash < 0) {
                    hasRootFile = true;
                    continue;
                }
                String top = name.substring(0, slash);
                if (firstTop == null) {
                    firstTop = top;
                } else if (!firstTop.equals(top)) {
                    return false;
                }
            }
        }
        return firstTop != null && !hasRootFile;
    }

    /** 只用于「先读一遍再决定」的场合（上传时把流落到临时文件后调用）。 */
    static InputStream open(Path archive) throws IOException {
        return Files.newInputStream(archive);
    }
}
