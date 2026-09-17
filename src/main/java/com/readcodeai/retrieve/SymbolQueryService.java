package com.readcodeai.retrieve;

import com.readcodeai.index.store.SymbolQueryRepository;
import com.readcodeai.retrieve.model.CallSiteView;
import com.readcodeai.retrieve.model.RepoView;
import com.readcodeai.retrieve.model.SymbolView;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 第 1 层检索（确定性查询）。
 *
 * <p>这一层**完全不涉及 LLM**：能查表算出答案的问题，绝不让模型去猜。
 * 没配 API Key 时，这里的能力照常可用 —— 可降级设计的具体体现。
 */
@Service
public class SymbolQueryService {

    private final SymbolQueryRepository repository;

    public SymbolQueryService(SymbolQueryRepository repository) {
        this.repository = repository;
    }

    public List<RepoView> repos() {
        return repository.listRepos();
    }

    /** {@code repoId} 为 null 时用最近一次索引完成的仓库。找不到任何仓库时报错，不静默返回空。 */
    public List<SymbolView> locate(Long repoId, String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("查询关键字不能为空");
        }
        long effectiveRepoId = repoId != null ? repoId : requireLatestRepoId();
        return repository.findSymbols(effectiveRepoId, keyword.strip(), Math.min(Math.max(limit, 1), 200));
    }

    public SymbolView requireSymbol(long symbolId) {
        SymbolView symbol = repository.findSymbolById(symbolId);
        if (symbol == null) {
            throw new NotFoundException("符号不存在：id=" + symbolId);
        }
        return symbol;
    }

    /** 谁调用了它。 */
    public List<CallSiteView> callers(long symbolId) {
        requireSymbol(symbolId);
        return repository.callers(symbolId);
    }

    /** 它调用了谁。未解析的边也在内，带 reason 供调用方决定是否展示。 */
    public List<CallSiteView> callees(long symbolId) {
        requireSymbol(symbolId);
        return repository.callees(symbolId);
    }

    /** 有哪些实现类 / 子类（直接关系）。 */
    public List<SymbolView> implementations(long symbolId) {
        requireSymbol(symbolId);
        return repository.directImplementations(symbolId);
    }

    public long requireLatestRepoId() {
        Long id = repository.latestReadyRepoId();
        if (id == null) {
            throw new NotFoundException("还没有任何索引完成的仓库，请先建立索引");
        }
        return id;
    }
}
