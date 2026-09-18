package com.readcodeai.index;

import com.readcodeai.config.ReadCodeAiProperties;
import com.readcodeai.index.model.CollectedCall;
import com.readcodeai.index.model.FileOutcome;
import com.readcodeai.index.parser.AnalyzeResult;
import com.readcodeai.index.parser.SourceAnalyzer;
import com.readcodeai.index.store.IndexRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * 索引编排：扫描 → 解析 → 落库 → 统计。
 *
 * <p>整个过程**不依赖 LLM**（这是可降级设计的底线：静态分析本来就不需要 AI）。
 */
@Service
public class ProjectIndexer {

    private static final Logger log = LoggerFactory.getLogger(ProjectIndexer.class);

    private final IndexRepository repository;
    private final ReadCodeAiProperties properties;
    private final RepoFetcher repoFetcher;

    public ProjectIndexer(IndexRepository repository, ReadCodeAiProperties properties, RepoFetcher repoFetcher) {
        this.repository = repository;
        this.properties = properties;
        this.repoFetcher = repoFetcher;
    }

    /**
     * 拉取远程仓库并索引 —— 「贴个链接就能用」的入口。
     *
     * <p>拉取与索引是两件事：拉取失败会直接报错（链接错、网络不通），
     * 而索引失败会由 {@link #index(Path)} 内部把仓库行标成 FAILED。
     */
    public IndexSummary indexRemote(String gitUrl) {
        RepoFetcher.Fetched fetched = repoFetcher.fetch(gitUrl, Path.of(properties.getIndex().getWorkspace()));
        // 源码包是快照、没有 .git，所以提交号从拉取结果带进来
        return index(fetched.root(), fetched.commitHash());
    }

    public IndexSummary index(Path repoRoot) {
        return index(repoRoot, null);
    }

    /**
     * @param commitHashOverride 远程拉取时带进来的提交号；为 null 时尝试从本地 {@code .git} 读
     */
    public IndexSummary index(Path repoRoot, String commitHashOverride) {
        long start = System.nanoTime();
        Path root = repoRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("不是目录：" + root);
        }
        String name = root.getFileName() == null ? root.toString() : root.getFileName().toString();
        String commitHash = commitHashOverride != null ? commitHashOverride : readCommitHash(root);
        long repoId = repository.beginRepo(name, root.toString(), commitHash);
        log.info("开始索引 {}（repoId={}, commit={}）", root, repoId, commitHash);

        try {
            List<Path> sourceRoots = findSourceRoots(root);
            List<Path> javaFiles = collectJavaFiles(root, sourceRoots);
            log.info("源码根 {} 个，Java 文件 {} 个", sourceRoots.size(), javaFiles.size());

            AnalyzeResult analyzed = new SourceAnalyzer(sourceRoots)
                    .analyze(root, javaFiles, properties.getIndex().getMaxFileSizeKb());

            long storeStart = System.nanoTime();
            Map<String, Long> fileIds = repository.insertSourceFiles(repoId, analyzed.files());
            Map<String, Long> symbolIds = repository.insertSymbols(repoId, analyzed.symbols(), fileIds);
            List<CollectedCall> edgeable = new ArrayList<>(analyzed.calls().size());
            int orphan = 0;
            for (CollectedCall call : analyzed.calls()) {
                if (symbolIds.containsKey(call.callerSymbolKey())) {
                    edgeable.add(call);
                } else {
                    // 调用者符号缺失说明「有调用、没有宿主方法」，这是分析器的问题，必须计数而不是静默丢弃
                    orphan++;
                }
            }
            repository.insertCalls(repoId, edgeable, symbolIds);
            repository.insertRelations(repoId, analyzed.relations(), symbolIds);
            repository.insertChunks(repoId, analyzed.chunks(), fileIds, symbolIds);

            int totalLoc = analyzed.files().stream().mapToInt(FileOutcome::loc).sum();
            int resolvedEdges = (int) edgeable.stream().filter(CollectedCall::resolved).count();
            repository.finishRepo(repoId, analyzed.files().size(), (int) analyzed.parsedOkCount(), totalLoc,
                    analyzed.symbols().size(), edgeable.size(), resolvedEdges);
            long storeMillis = (System.nanoTime() - storeStart) / 1_000_000;

            IndexSummary summary = new IndexSummary(repoId, name, root.toString(), commitHash,
                    analyzed.files().size(), (int) analyzed.parsedOkCount(), totalLoc,
                    analyzed.symbols().size(), edgeable.size(), resolvedEdges, analyzed.chunks().size(), orphan,
                    repository.unresolvedReasonCounts(repoId),
                    analyzed.parseMillis(), analyzed.resolveMillis(), storeMillis,
                    (System.nanoTime() - start) / 1_000_000);
            log.info("索引完成：{}", summary.toReport().replace(System.lineSeparator(), " | "));
            return summary;
        } catch (RuntimeException | LinkageError e) {
            // 兜底：真出现预料之外的错误时，repo 行必须被标成 FAILED 而不是留在 INDEXING 状态
            repository.failRepo(repoId, e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
    }

    /**
     * 找源码根：优先所有 {@code src/main/java}；找不到就退回仓库根（应对非标准布局的项目）。
     * 非标准布局在真实仓库里很常见（JDK 的 src.zip 就是），所以必须有这条退路。
     */
    List<Path> findSourceRoots(Path root) {
        List<Path> roots = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isDirectory)
                    .filter(p -> normalize(p).endsWith("src/main/java"))
                    .filter(p -> !normalize(p).contains("/target/"))
                    .forEach(roots::add);
        } catch (IOException e) {
            throw new UncheckedIOException("扫描源码根失败：" + root, e);
        }
        if (roots.isEmpty()) {
            roots.add(root);
        }
        return roots;
    }

    List<Path> collectJavaFiles(Path root, List<Path> sourceRoots) {
        List<PathMatcher> excludes = properties.getIndex().getExcludePatterns().stream()
                .map(pattern -> FileSystems.getDefault().getPathMatcher("glob:" + pattern))
                .toList();
        TreeSet<Path> files = new TreeSet<>();
        for (Path sourceRoot : sourceRoots) {
            try (Stream<Path> walk = Files.walk(sourceRoot)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".java"))
                        .filter(p -> !isExcluded(root, p, excludes))
                        .forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException("扫描源文件失败：" + sourceRoot, e);
            }
        }
        return new ArrayList<>(files);
    }

    private boolean isExcluded(Path root, Path file, List<PathMatcher> excludes) {
        Path relative = root.relativize(file);
        Path normalized = Paths.get(relative.toString().replace('\\', '/'));
        return excludes.stream().anyMatch(matcher -> matcher.matches(normalized));
    }

    /**
     * 从 .git 目录直接读提交号，不引 JGit。
     * 只处理最常见的 {@code .git/HEAD -> refs/...}；worktree / packed-refs 取不到就返回 null，
     * 宁可为空也不猜（这个值会用来标注「索引基于哪个版本」）。
     */
    String readCommitHash(Path root) {
        Path head = root.resolve(".git/HEAD");
        try {
            if (!Files.isRegularFile(head)) {
                return null;
            }
            String content = Files.readString(head, StandardCharsets.UTF_8).strip();
            if (!content.startsWith("ref:")) {
                return content.isEmpty() ? null : content;
            }
            Path refFile = root.resolve(".git/" + content.substring("ref:".length()).strip());
            if (!Files.isRegularFile(refFile)) {
                return null;
            }
            String value = Files.readString(refFile, StandardCharsets.UTF_8).strip();
            return value.isEmpty() ? null : value;
        } catch (IOException e) {
            log.debug("读取 git 提交号失败：{}", e.getMessage());
            return null;
        }
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }
}
