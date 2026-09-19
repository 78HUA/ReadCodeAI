package com.readcodeai.retrieve;

import com.readcodeai.index.store.SymbolQueryRepository;
import com.readcodeai.retrieve.model.RepoView;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 读出**磁盘上真实的文件内容** —— 前端「证据卡片点开看代码」用的就是它。
 *
 * <p>三个要点，每个都对应一条前面立下的规矩：
 * <ol>
 *   <li><b>读磁盘，不读索引里的副本</b>：索引里的 chunk 是建立索引那一刻的快照，
 *       而用户点开证据想看的是"这行现在到底是什么"</li>
 *   <li><b>顺带告诉用户"索引之后这个文件有没有被改过"</b>：比较磁盘当前哈希与索引时记录的哈希。
 *       不一致时，行号可能已经漂移 —— 这件事必须让人看见，而不是让人对着错位的代码猜</li>
 *   <li><b>路径必须落在仓库内</b>：路径来自前端，属于不可信输入；
 *       还原成绝对路径后要检查没有越出仓库根（与证据核验同一道防线）</li>
 * </ol>
 */
@Service
public class FileContentService {

    /** 一次最多返回多少行：证据卡片是让人核对几行代码的，不是给人看整份文件的。 */
    private static final int MAX_WINDOW_LINES = 400;

    private final SymbolQueryService queries;
    private final SymbolQueryRepository repository;

    public FileContentService(SymbolQueryService queries, SymbolQueryRepository repository) {
        this.queries = queries;
        this.repository = repository;
    }

    /**
     * @param startLine 1 起的起始行；不传则从第 1 行开始
     * @param endLine   含结束行；不传则取到文件末尾（仍受 {@link #MAX_WINDOW_LINES} 限制）
     */
    public FileContent read(long repoId, String path, Integer startLine, Integer endLine) {
        RepoView repo = queries.requireRepo(repoId);
        Path root = Path.of(repo.rootPath()).toAbsolutePath().normalize();
        Path file;
        try {
            file = root.resolve(path.replace('\\', '/')).normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("路径不合法：" + path);
        }
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("路径越出仓库范围：" + path);
        }
        if (!Files.isRegularFile(file)) {
            throw new NotFoundException("仓库里没有这个文件：" + path);
        }

        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取文件失败：" + path + " —— " + e.getMessage(), e);
        }

        List<String> all = text.lines().toList();
        int from = startLine == null || startLine < 1 ? 1 : Math.min(startLine, Math.max(all.size(), 1));
        int to = endLine == null || endLine < from ? all.size() : Math.min(endLine, all.size());
        to = Math.min(to, from + MAX_WINDOW_LINES - 1);

        List<Line> lines = new ArrayList<>();
        for (int number = from; number <= to && number <= all.size(); number++) {
            lines.add(new Line(number, all.get(number - 1)));
        }

        String diskHash = sha256(text);
        String indexedHash = repository.contentHash(repoId, path).orElse(null);
        return new FileContent(path, from, to, all.size(), lines, indexedHash, diskHash,
                indexedHash != null && !indexedHash.equals(diskHash));
    }

    /** 一行带行号的原文。 */
    public record Line(int number, String text) {
    }

    /**
     * @param changedSinceIndex 磁盘内容与索引时不一致 —— 行号可能已经漂移，界面上必须提示
     */
    public record FileContent(
            String path,
            int startLine,
            int endLine,
            int totalLines,
            List<Line> lines,
            String indexedHash,
            String diskHash,
            boolean changedSinceIndex) {
    }

    /** 与索引层同一个算法（{@code SourceAnalyzer.sha256}）：都是对 UTF-8 文本做 SHA-256，才可比。 */
    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 必须提供 SHA-256", e);
        }
    }
}
