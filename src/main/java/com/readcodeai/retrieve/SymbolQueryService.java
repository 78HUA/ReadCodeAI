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

    /** 按仓库根路径锁定一个语料（测试与脚本用；别依赖「最近索引的仓库」那个会变的状态）。 */
    public java.util.Optional<RepoView> findByRootPath(String rootPath) {
        return repository.findByRootPath(rootPath);
    }

    /** 按 id 取仓库；找不到就报错 —— 证据校验要靠它的根路径把相对路径还原成磁盘文件。 */
    public RepoView requireRepo(long repoId) {
        return repository.findRepoById(repoId)
                .orElseThrow(() -> new NotFoundException("仓库不存在：id=" + repoId));
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

    /** 一个类型的直接成员（方法/字段/构造器）—— 结构题用它。 */
    public List<SymbolView> children(long symbolId) {
        requireSymbol(symbolId);
        return repository.children(symbolId);
    }

    /** 在指定类型里按名字找成员（限定查找：解决"裸方法名不唯一"的问题）。 */
    public List<SymbolView> findMembersInType(long repoId, String typeQualifiedName, String name) {
        return repository.findMembersInType(repoId, typeQualifiedName, name);
    }

    public long requireLatestRepoId() {
        Long id = repository.latestReadyRepoId();
        if (id == null) {
            throw new NotFoundException("还没有任何索引完成的仓库，请先建立索引");
        }
        return id;
    }
}
