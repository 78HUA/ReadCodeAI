package com.readcodeai.index;

import com.readcodeai.config.ReadCodeAiProperties;
import com.readcodeai.index.model.CollectedCall;
import com.readcodeai.index.model.CollectedChunk;
import com.readcodeai.index.model.FileOutcome;
import com.readcodeai.index.parser.AnalyzeResult;
import com.readcodeai.index.parser.SourceAnalyzer;
import com.readcodeai.index.parser.TextFileChunker;
import com.readcodeai.index.store.IndexRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
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
    private final TransactionTemplate transactionTemplate;

    public ProjectIndexer(IndexRepository repository, ReadCodeAiProperties properties, RepoFetcher repoFetcher,
                          TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.properties = properties;
        this.repoFetcher = repoFetcher;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 落库阶段的结果：**事务里算好的东西一次带出来**。
     *
     * <p>为什么不用"往 lambda 外的数组里塞"那种写法：那样每加一个返回值就要多一个容器，
     * 读代码的人得自己去数"这个数组第几个是什么"。一个 record 把进出讲清楚。
     */
    private record Stored(Map<String, Long> symbolIds, List<CollectedCall> edgeable, int orphan,
                          int textChunkCount,
                          long filesMs, long symbolsMs, long callsMs, long relationsMs, long chunksMs,
                          long otherMs) {
    }

    /**
     * 落库的**全部写入**（含标 READY），在调用方给的事务里执行。
     *
     * <p>顺序不能变：文件 → 符号 → 调用边 → 类型关系 → 代码块 —— 后三张表都要用前两张表的 id 映射。
     */
    private Stored storeIndex(long repoId, AnalyzeResult analyzed,
                              List<TextFileChunker.TextChunks> textFiles) {
        long phaseStart = System.nanoTime();
        Map<String, Long> fileIds = new HashMap<>(repository.insertSourceFiles(repoId, analyzed.files()));
        if (!textFiles.isEmpty()) {
            // 文本文件也要有 source_file 行（chunk 的外键指过去），但 kind='TEXT'：
            // 它们不参与"解析成功率"与"模块划分"，只让内容变得可检索
            fileIds.putAll(repository.insertTextFiles(repoId,
                    textFiles.stream().map(TextFileChunker.TextChunks::file).toList()));
        }
        long filesMs = (System.nanoTime() - phaseStart) / 1_000_000;

        phaseStart = System.nanoTime();
        Map<String, Long> symbolIds = repository.insertSymbols(repoId, analyzed.symbols(), fileIds);
        long symbolsMs = (System.nanoTime() - phaseStart) / 1_000_000;

        phaseStart = System.nanoTime();
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
        long callsMs = (System.nanoTime() - phaseStart) / 1_000_000;

        phaseStart = System.nanoTime();
        repository.insertRelations(repoId, analyzed.relations(), symbolIds);
        long relationsMs = (System.nanoTime() - phaseStart) / 1_000_000;

        phaseStart = System.nanoTime();
        List<CollectedChunk> allChunks = new ArrayList<>(analyzed.chunks());
        textFiles.forEach(textFile -> allChunks.addAll(textFile.chunks()));
        repository.insertChunks(repoId, allChunks, fileIds, symbolIds);
        long chunksMs = (System.nanoTime() - phaseStart) / 1_000_000;

        // 标 READY 放在同一个事务里：提交那一刻"数据 + 状态"一起生效，
        // 不会出现"数据其实写完了、状态还停在 INDEXING"的中间态
        phaseStart = System.nanoTime();
        repository.finishRepo(repoId, analyzed.files().size(), (int) analyzed.parsedOkCount(),
                analyzed.files().stream().mapToInt(FileOutcome::loc).sum(),
                analyzed.symbols().size(), edgeable.size(),
                (int) edgeable.stream().filter(CollectedCall::resolved).count());
        long otherMs = (System.nanoTime() - phaseStart) / 1_000_000;

        return new Stored(symbolIds, edgeable, orphan,
                allChunks.size() - analyzed.chunks().size(),
                filesMs, symbolsMs, callsMs, relationsMs, chunksMs, otherMs);
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
     * 建好仓库行（状态 INDEXING）并返回 repoId —— **异步任务的第一半步**。
     *
     * <p>把"建行"和"跑索引"拆开，是因为异步场景下这两件事发生的时间不一样：
     * 接单时就要能告诉调用方一个 repoId，而干活还在后面。
     */
    public long createPendingRepo(Path repoRoot, String commitHashOverride) {
        Path root = repoRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("不是目录：" + root);
        }
        String name = root.getFileName() == null ? root.toString() : root.getFileName().toString();
        String commitHash = commitHashOverride != null ? commitHashOverride : readCommitHash(root);
        return repository.beginRepo(name, root.toString(), commitHash);
    }

    /**
     * @param commitHashOverride 远程拉取时带进来的提交号；为 null 时尝试从本地 {@code .git} 读
     */
    public IndexSummary index(Path repoRoot, String commitHashOverride) {
        Path root = repoRoot.toAbsolutePath().normalize();
        long repoId = createPendingRepo(root, commitHashOverride);
        String name = root.getFileName() == null ? root.toString() : root.getFileName().toString();
        String commitHash = commitHashOverride != null ? commitHashOverride : readCommitHash(root);
        return indexInto(repoId, root, name, commitHash, ProgressListener.NOOP);
    }

    /**
     * 索引的**主体**：仓库行已经建好了，这里只干活。
     *
     * <p>同步路径（{@link #index(Path, String)}）与异步路径（{@code AsyncIndexer}）都走它 ——
            long storeStart = System.nanoTime();

    /** 上传压缩包的大小上限：与 GitHub 源码包同一个量级，够放一个中等仓库。 */
    public static final long MAX_UPLOAD_BYTES = 200L * 1024 * 1024;

    /**
     * 索引一个**上传上来的压缩包**。
     *
     * <p>三件事在这里一次做完，控制器只管收字节流：
     * <ol>
     *   <li>落到工作区（边写边计体积，超限立即中止）</li>
     *   <li>解压 —— 路径校验与体积上限交给 {@link ZipExtractor}（与远程拉取共用一份）</li>
     *   <li>索引</li>
     * </ol>
     *
     * <p><b>剥不剥最外层目录要看压缩包本身</b>：压缩包里只有一层目录时剥掉（"把项目文件夹压进去"的常见形态），
     * 否则原样解压 —— 剥错了会把 {@code src} 当成包装目录扔掉，用户只会看到"索引出 0 个文件"。
     */
    public IndexSummary indexArchive(java.io.InputStream zipStream, String originalFilename) {
        String name = safeArchiveName(originalFilename);
        Path workspace = Path.of(properties.getIndex().getWorkspace()).resolve("uploads");
        Path archive = null;
        try {
            Files.createDirectories(workspace);
            archive = Files.createTempFile(workspace, "upload-", ".zip");
            long copied = copyWithLimit(zipStream, archive);
            log.info("收到上传压缩包：{}（{} 字节）→ 解压目录 {}", originalFilename, copied, name);
            Path root = workspace.resolve(name);
            // 同一个名字重新上传 = 覆盖：先把旧内容清干净，否则索引里会混进上一次的文件
            ZipExtractor.clearDirectory(root);
            ZipExtractor.extract(archive, root, ZipExtractor.hasSingleTopLevelDirectory(archive));
            return index(root);
        } catch (IOException e) {
            throw new IllegalStateException("处理上传压缩包失败：" + e.getMessage(), e);
        } finally {
            if (archive != null) {
                try {
                    Files.deleteIfExists(archive);
                } catch (IOException e) {
                    log.warn("清理临时压缩包失败（不影响索引结果）：{}", e.getMessage());
                }
            }
        }
    }

    /** 删除某个仓库的索引（外键是 CASCADE，子表会跟着删）。 */
    public boolean deleteIndex(long repoId) {
        boolean deleted = repository.deleteRepo(repoId);
        log.info("删除索引：repoId={} · {}", repoId, deleted ? "已删除" : "不存在");
        return deleted;
    }

    private static long copyWithLimit(java.io.InputStream in, Path target) throws IOException {
        long copied = 0;
        try (java.io.OutputStream out = Files.newOutputStream(target,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                copied += read;
                if (copied > MAX_UPLOAD_BYTES) {
                    throw new IOException("压缩包超过 " + (MAX_UPLOAD_BYTES / 1024 / 1024) + " MB，已中止");
                }
                out.write(buffer, 0, read);
            }
        }
        if (copied == 0) {
            throw new IOException("压缩包是空的");
        }
        return copied;
    }

    /** 压缩包名 → 安全的目录名：只留字母数字与点线，且不带头部的路径分隔符。 */
    public static String safeArchiveName(String originalFilename) {
        String base = originalFilename == null ? "" : originalFilename.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        if (base.toLowerCase().endsWith(".zip")) {
            base = base.substring(0, base.length() - 4);
        }
        String cleaned = base.replaceAll("[^A-Za-z0-9._-]", "-").replaceAll("^-+|-+$", "");
        return cleaned.isBlank() ? "uploaded-repo" : cleaned;
    }

    /**
     * 索引的**主体**：仓库行已经建好了，这里只干活。
     *
     * <p>同步路径（{@link #index(Path, String)}）与异步路径（{@code AsyncIndexer}）都走它 ——
     * 同一条流水线，区别只在"谁报进度"和"谁来调用"。
     *
     * @param listener 进度回调；每个文件解析完都会报一次（实现在写库前自行节流）
     */
    public IndexSummary indexInto(long repoId, Path root, String name, String commitHash,
                                  ProgressListener listener) {
        long start = System.nanoTime();
        log.info("开始索引 {}（repoId={}, commit={}）", root, repoId, commitHash);

        try {
            listener.onProgress("SCANNING", 0, 0, "扫描源文件");
            List<Path> sourceRoots = findSourceRoots(root);
            List<Path> javaFiles = collectJavaFiles(root, sourceRoots);
            log.info("源码根 {} 个，Java 文件 {} 个", sourceRoots.size(), javaFiles.size());
            listener.onProgress("PARSING", 0, javaFiles.size(), "解析 " + javaFiles.size() + " 个文件");

            AnalyzeResult analyzed = new SourceAnalyzer(sourceRoots, properties.getIndex().getParseThreads())
                    .analyze(root, javaFiles, properties.getIndex().getMaxFileSizeKb(), listener);

            // 文本文件（配置 / SQL / 文档 / 前端源码）：**只做检索**，不进符号表、不算解析统计
            List<TextFileChunker.TextChunks> textFiles = chunkTextFiles(root);

            listener.onProgress("STORING", 0, 0, "写入索引（符号 / 调用图 / 检索单元）");
            long storeStart = System.nanoTime();
            // 落库整段**一个事务**，理由两条：
            // ① 语义：要么整份索引都在，要么一条都不留 —— 早先崩在中间会留下半份数据，只靠 repo.status 兜着；
            // ② 性能：实测落库占索引总耗时 70%，而其中大部分是"每条 INSERT 一次自动提交"（每条都是一次落盘）。
            // 事务边界刻意不含进度回调（回调都在这个块之外）：进度写在另一个事务里才不会被压到提交后可见。
            Stored stored = transactionTemplate.execute(status -> storeIndex(repoId, analyzed, textFiles));
            long storeMillis = (System.nanoTime() - storeStart) / 1_000_000;
            List<CollectedCall> edgeable = stored.edgeable();
            int orphan = stored.orphan();
            log.info("落库分解：文件 {} ms · 符号 {} ms · 调用边 {} ms · 类型关系 {} ms · 代码块 {} ms"
                            + " · 标 READY + 提交 {} ms（合计 {} ms）",
                    stored.filesMs(), stored.symbolsMs(), stored.callsMs(), stored.relationsMs(),
                    stored.chunksMs(), stored.otherMs(), storeMillis);

            // 全文索引维护：碎片是索引过程自己产生的，收尾时按需重建（详见 IndexRepository 的注释）
            int chunkTotal = analyzed.chunks().size() + stored.textChunkCount();
            int optimizeThreshold = properties.getIndex().getOptimizeFulltextThreshold();
            if (optimizeThreshold > 0 && chunkTotal >= optimizeThreshold) {
                long optimizeStart = System.nanoTime();
                repository.optimizeFulltextIndex();
                log.info("全文索引已重建（{} 个检索单元 · 耗时 {} ms）：重复索引会留碎片，"
                                + "实测能把检索从 65 ms 拖到 13 秒",
                        chunkTotal, (System.nanoTime() - optimizeStart) / 1_000_000);
            }

            int totalLoc = analyzed.files().stream().mapToInt(FileOutcome::loc).sum();
            int resolvedEdges = (int) edgeable.stream().filter(CollectedCall::resolved).count();
            IndexSummary summary = new IndexSummary(repoId, name, root.toString(), commitHash,
                    analyzed.files().size(), (int) analyzed.parsedOkCount(), totalLoc,
                    analyzed.symbols().size(), edgeable.size(), resolvedEdges,
                    analyzed.chunks().size() + stored.textChunkCount(),
                    (int) textFiles.stream().filter(f -> f.file().parsedOk()).count(), orphan,
                    repository.unresolvedReasonCounts(repoId),
                    analyzed.parseMillis(), analyzed.resolveMillis(), storeMillis,
                    (System.nanoTime() - start) / 1_000_000);
            listener.onProgress("DONE", analyzed.files().size(), analyzed.files().size(),
                    "索引完成：" + analyzed.files().size() + " 个文件 · " + summary.symbolCount() + " 个符号");
            log.info("索引完成：{}", summary.toReport().replace(System.lineSeparator(), " | "));
            return summary;
        } catch (RuntimeException | LinkageError e) {
            // 兜底：真出现预料之外的错误时，repo 行必须被标成 FAILED 而不是留在 INDEXING 状态
            listener.onProgress("FAILED", 0, 0, e.getClass().getSimpleName() + ": " + e.getMessage());
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

    /**
     * 收集并切开文本文件。
     *
     * <p>扫描范围是**仓库根**（不是 sourceRoots）：{@code pom.xml}、{@code application.yml}、{@code README.md}
     * 都在根目录下，而 sourceRoots 是 {@code src/main/java} 这类目录。排除规则与 Java 文件共用同一批 glob。
     */
    List<TextFileChunker.TextChunks> chunkTextFiles(Path root) {
        List<PathMatcher> excludes = excludeMatchers();
        List<Path> textFiles = TextFileChunker.collect(root, excludes);
        int maxFileSizeKb = properties.getIndex().getMaxFileSizeKb();
        TextFileChunker chunker = new TextFileChunker();
        List<TextFileChunker.TextChunks> chunked = new ArrayList<>(textFiles.size());
        for (Path file : textFiles) {
            String relativePath = root.relativize(file).toString().replace('\\', '/');
            chunked.add(chunker.chunk(file, relativePath, maxFileSizeKb));
        }
        return chunked;
    }

    private List<PathMatcher> excludeMatchers() {
        return properties.getIndex().getExcludePatterns().stream()
                .map(pattern -> FileSystems.getDefault().getPathMatcher("glob:" + pattern))
                .toList();
    }

    List<Path> collectJavaFiles(Path root, List<Path> sourceRoots) {
        List<PathMatcher> excludes = excludeMatchers();
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
