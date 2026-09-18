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
        int capped = Math.min(Math.max(limit, 1), 100);

        // 先精确后放宽：短查询（如一个标识符）用「全部词都必须命中」，
        // 拿不到结果再放宽成「任一词命中」—— 自然语言长句走的是后一条路。
        List<ChunkHit> precise = repository.searchPhrases(effectiveRepoId, tokens, true, capped);
        return precise.isEmpty()
                ? repository.searchPhrases(effectiveRepoId, tokens, false, capped)
                : precise;
    }

    /**
     * 把查询切成检索短语。
     *
     * <p>规则（两条都是被实测逼出来的，别当成可选的优化）：
     * <ul>
     *   <li><b>ASCII 标识符整段保留</b>：{@code uploadMusic} 是一个词，切成 upload / Music 会失准</li>
     *   <li><b>中文按二元组切</b>：中文没有空格，整句会变成一个 token —— 实测
     *       「登录检查是在哪里做的？」这样切出来是 {@code 登录检查是在哪里做的} 一整串，
     *       短语检索永远命中不了（0 条结果）。按二元组切开才搜得到，
     *       而且二元组正是 ngram 索引的粒度，两边对齐。</li>
     * </ul>
     *
     * <p><b>已知局限</b>：这只是「够用的中文切分」，不是真正的分词 ——
     * 它会把「是在」「哪里」这类虚词也当检索词，靠后面的排序去压。
     * 真正的语义检索是第 3 层（向量），第一版不做。
     */
    static List<String> tokenize(String query) {
        // 两类词分开收集：截断时**优先保 ASCII 标识符**。
        // 理由是被实测逼出来的：中文问句的二元组很容易占满配额，把类名/方法名这些真正的线索挤掉 ——
        // 对英文代码库问「这个类大致是做什么的：com.google.gson.Gson？」曾因此命中 0 条。
        // 标识符是精确 token，二元组只是"够用的中文切分"，谁更值钱一目了然。
        LinkedHashSet<String> asciiTokens = new LinkedHashSet<>();
        LinkedHashSet<String> cjkTokens = new LinkedHashSet<>();
        StringBuilder ascii = new StringBuilder();
        StringBuilder cjk = new StringBuilder();

        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (isCjk(c)) {
                addAscii(asciiTokens, ascii);
                cjk.append(c);
            } else if (c == '_' || c == '$' || Character.isLetterOrDigit(c)) {
                addCjk(cjkTokens, cjk);
                ascii.append(c);
            } else {
                addAscii(asciiTokens, ascii);
                addCjk(cjkTokens, cjk);
            }
        }
        addAscii(asciiTokens, ascii);
        addCjk(cjkTokens, cjk);

        List<String> result = new ArrayList<>(asciiTokens);
        for (String token : cjkTokens) {
            if (result.size() >= MAX_TOKENS) {
                break;
            }
            result.add(token);
        }
        return result;
    }

    /** 汉字（含扩展区）判定。注意 {@code Character.isLetterOrDigit} 对汉字也返回 true，所以必须先判它。 */
    private static boolean isCjk(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }

    private static void addAscii(LinkedHashSet<String> tokens, StringBuilder buffer) {
        if (buffer.length() >= MIN_TOKEN_LENGTH) {
            tokens.add(buffer.toString());
        }
        buffer.setLength(0);
    }

    private static void addCjk(LinkedHashSet<String> tokens, StringBuilder buffer) {
        String run = buffer.toString();
        buffer.setLength(0);
        if (run.length() == 1) {
            tokens.add(run);
            return;
        }
        // 滑动二元组：两个汉字就是中文里最常见的最小语义单位
        for (int i = 0; i + 2 <= run.length(); i++) {
            tokens.add(run.substring(i, i + 2));
        }
    }
}
