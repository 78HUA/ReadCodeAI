package com.readcodeai.retrieve;

import com.readcodeai.index.store.TextSearchRepository;
import com.readcodeai.retrieve.model.ChunkHit;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 第 2 层检索：全文检索。
 *
 * <p>服务的问题类型是「**哪个文件处理文件上传**」这类 —— 靠的是**标识符与注释里的精确词**，
 * 不是语义相似度。所以这里一次向量计算都没有。
 *
 * <p><b>为什么不把用户输入直接丢给数据库</b>：索引用 ngram 解析器（为了能搜中文注释），
 * 而 ngram 对含标点的输入会失效 —— 实测 {@code R.success} 直接检索命中 0 条，
 * 拆成短语 {@code success} 之后才和人工基准一致。所以切词是**正确性的一部分**，不是优化。
 */
@Service
public class TextRetriever {

    /** 太短的词（单字符、单个汉字之外）检索价值低，反而会拉进大量噪声。 */
    private static final int MIN_TOKEN_LENGTH = 2;

    /** 查询里最多取几个词做短语检索，避免超长输入把布尔查询撑爆。 */
    private static final int MAX_TOKENS = 8;

    private final TextSearchRepository repository;
    private final SymbolQueryService symbolQueryService;

    public TextRetriever(TextSearchRepository repository, SymbolQueryService symbolQueryService) {
        this.repository = repository;
        this.symbolQueryService = symbolQueryService;
    }

    public List<ChunkHit> search(Long repoId, String query, int limit) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("查询内容不能为空");
        }
        List<String> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException(
                    "查询里没有可用于检索的词（至少 " + MIN_TOKEN_LENGTH + " 个字符）");
        }
        long effectiveRepoId = repoId != null ? repoId : symbolQueryService.requireLatestRepoId();
        return repository.searchPhrases(effectiveRepoId, tokens,
                Math.min(Math.max(limit, 1), 100));
    }

    /**
     * 把查询切成短语：按**非标识符字符**切分（点、井号、空格、括号都是分隔符），
     * 丢掉过短的词并去重。
     *
     * <p>于是 {@code R.success} → {@code success}，{@code MusicService#uploadMusic} → {@code MusicService, uploadMusic}。
     */
    static List<String> tokenize(String query) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
                current.append(c);
            } else {
                addToken(tokens, current.toString());
                current.setLength(0);
            }
        }
        addToken(tokens, current.toString());

        List<String> result = new ArrayList<>(tokens);
        return result.size() <= MAX_TOKENS ? result : result.subList(0, MAX_TOKENS);
    }

    private static void addToken(LinkedHashSet<String> tokens, String token) {
        if (token.length() >= MIN_TOKEN_LENGTH) {
            tokens.add(token);
        }
    }
}
