package com.readcodeai.index;

import com.readcodeai.config.ReadCodeAiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 把 GitHub 仓库拉下来（贴个链接就能用）。
 *
 * <h3>为什么用源码包（zipball）而不是 git clone</h3>
 * <ol>
 *   <li><b>通道更可靠</b>：本机实测 {@code github.com:443} 间歇不通，而 {@code codeload.github.com}
 *       稳定 —— 而 git 协议走的正是前者</li>
 *   <li><b>我们只需要某个提交的工作区快照</b>，不需要 git 历史，源码包更小更快</li>
 *   <li><b>不用引新依赖</b>：JDK 原生支持 zip（{@code ZipInputStream}），但**没有 tar 支持** ——
 *       选 zipball 就不必引入 commons-compress，也不必调用外部 {@code tar} 命令</li>
 * </ol>
 *
 * <h3>安全约束（别人贴的链接不能当命令执行）</h3>
 * <ul>
 *   <li><b>主机白名单</b>：只允许 github.com —— 否则贴个内网地址就是一个 SSRF</li>
 *   <li><b>下载体积上限</b>：边下边计数，超限立即中止</li>
 *   <li><b>解压体积上限 + 路径校验</b>：防 zip 炸弹；每个条目解析后必须仍在目标目录内（防 zip slip）</li>
 * </ul>
 */
@Service
public class RepoFetcher {

    private static final Logger log = LoggerFactory.getLogger(RepoFetcher.class);

    private static final Set<String> ALLOWED_HOSTS = Set.of("github.com", "www.github.com");

    /** 源码包下载上限 200 MB。 */
    private static final long MAX_ARCHIVE_BYTES = 200L * 1024 * 1024;

    /** 解压后总大小上限 2 GB，防 zip 炸弹（与上传入口共用同一份实现，见 {@link ZipExtractor}）。 */
    private static final long MAX_EXTRACTED_BYTES = ZipExtractor.MAX_EXTRACTED_BYTES;

    /**
     * {@code /zip/HEAD} 直接取默认分支最新快照 —— **实测可用**，所以不必先调一次 API 查默认分支。
     */
    private static final String ZIPBALL_URL = "https://codeload.github.com/%s/%s/zip/HEAD";

    private final ReadCodeAiProperties properties;
    private final HttpClient httpClient;

    public RepoFetcher(ReadCodeAiProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    /** 拉取结果。{@code commitHash} 可能是 null（拿不到提交号不该阻塞索引）。 */
    public record Fetched(String sourceUrl, String owner, String repo, String commitHash, Path root) {
    }

    public Fetched fetch(String gitUrl, Path workspace) {
        RepositoryRef ref = parse(gitUrl);
        try {
            Files.createDirectories(workspace);
            Path archive = Files.createTempFile(workspace, ref.repo() + "-", ".zip");
            try {
                download(ref, archive);
                ZipExtractor.clearDirectory(workspace.resolve(ref.repo()));
                ZipExtractor.extract(archive, workspace.resolve(ref.repo()), true);
                Path root = workspace.resolve(ref.repo());
                String commitHash = lookupCommitHash(ref);
                log.info("已拉取 {} → {}（提交号 {}）", gitUrl, root, commitHash);
                return new Fetched(gitUrl, ref.owner(), ref.repo(), commitHash, root);
            } finally {
                Files.deleteIfExists(archive);
            }
        } catch (IOException e) {
            throw new IllegalStateException("拉取仓库失败：" + gitUrl + " —— " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("拉取仓库被中断：" + gitUrl, e);
        }
    }

    // ------------------------------------------------------------------ URL 解析

    /** 解析出 owner / repo。允许结尾带 {@code .git}，也允许后面跟 {@code /tree/xxx} 这类路径。 */
    static RepositoryRef parse(String gitUrl) {
        URI uri;
        try {
            uri = URI.create(gitUrl.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不是合法的 URL：" + gitUrl);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("只接受 https 链接：" + gitUrl);
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        if (!ALLOWED_HOSTS.contains(host)) {
            // SSRF 防线：允许任意主机等于把内网地址也交出去执行
            throw new IllegalArgumentException("只允许 " + ALLOWED_HOSTS + "，收到：" + host);
        }
        List<String> segments = List.of(uri.getPath().split("/")).stream()
                .filter(s -> !s.isBlank()).toList();
        if (segments.size() < 2) {
            throw new IllegalArgumentException("链接里缺少 owner/repo：" + gitUrl);
        }
        String repo = segments.get(1).endsWith(".git")
                ? segments.get(1).substring(0, segments.get(1).length() - 4)
                : segments.get(1);
        return new RepositoryRef(segments.get(0), repo);
    }

    record RepositoryRef(String owner, String repo) {
    }

    // ------------------------------------------------------------------ 下载与解压

    private void download(RepositoryRef ref, Path archive) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(ZIPBALL_URL.formatted(ref.owner(), ref.repo())))
                .timeout(Duration.ofMinutes(5))
                .GET();
        // 私有仓库可选：配了令牌就用，没配就匿名（公开仓库够用）
        String token = System.getenv("READCODEAI_GITHUB_TOKEN");
        if (token != null && !token.isBlank()) {
            request.header("Authorization", "Bearer " + token);
        }

        HttpResponse<InputStream> response =
                httpClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("下载失败，HTTP " + response.statusCode()
                    + "（仓库不存在、或不是公开仓库）");
        }

        long copied = 0;
        try (InputStream in = response.body();
             OutputStream out = Files.newOutputStream(archive, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                copied += read;
                if (copied > MAX_ARCHIVE_BYTES) {
                    throw new IOException("源码包超过 " + (MAX_ARCHIVE_BYTES / 1024 / 1024) + " MB，已中止");
                }
                out.write(buffer, 0, read);
            }
        }
        log.info("源码包下载完成：{} 字节", copied);
    }

    /**
     * 取默认分支最新提交号，用于记录「这份索引基于哪个版本」。
     * **拿不到不阻塞索引** —— 提交号是溯源信息，不是索引的必要条件。
     */
    private String lookupCommitHash(RepositoryRef ref) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/%s/%s/commits?per_page=1"
                            .formatted(ref.owner(), ref.repo())))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/vnd.github+json")
                    .GET();
            String token = System.getenv("READCODEAI_GITHUB_TOKEN");
            if (token != null && !token.isBlank()) {
                request.header("Authorization", "Bearer " + token);
            }
            HttpResponse<String> response =
                    httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return null;
            }
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("\"sha\"\\s*:\\s*\"([a-f0-9]{40})\"").matcher(response.body());
            return matcher.find() ? matcher.group(1) : null;
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.debug("取提交号失败（不影响索引）：{}", e.getMessage());
            return null;
        }
    }
}
