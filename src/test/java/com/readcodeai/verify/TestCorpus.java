package com.readcodeai.verify;

import com.readcodeai.index.ProjectIndexer;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.RepoView;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 测试语料的统一入口：**把「本次要测的那个语料」索引好，并给出它的 repoId**。
 *
 * <p><b>为什么需要它</b>：早先的测试直接用「最近一次索引的仓库」，那是个会变的全局状态 ——
 * 实测踩过：拉取了 gson 之后，针对 reggie 写的那几个测试全红，因为最新的仓库变成了 gson。
 * 生产代码用「最近仓库」当默认值没问题（单用户场景），但**测试必须指名道姓**。
 *
 * <p>语料路径由 {@code -Dreadcodeai.verify.repo=<路径>} 指定，默认 {@code sample-repos}
 * （已在 .gitignore 内）。语料不存在就返回空，让调用方 skip —— 而不是报错。
 */
public final class TestCorpus {

    public static final Path SAMPLE = Path.of(
            System.getProperty("readcodeai.verify.repo", "sample-repos"));

    private TestCorpus() {
    }

    /**
     * 拿到语料的仓库记录；没索引过就先索引（幂等：同一路径重复索引是覆盖行为）。
     *
     * @return 语料不存在时返回空
     */
    public static Optional<RepoView> resolve(ProjectIndexer indexer, SymbolQueryService queries) {
        if (!Files.isDirectory(SAMPLE)) {
            return Optional.empty();
        }
        String rootPath = SAMPLE.toAbsolutePath().normalize().toString();
        Optional<RepoView> existing = queries.findByRootPath(rootPath);
        if (existing.isPresent() && "READY".equals(existing.get().status())) {
            return existing;
        }
        indexer.index(SAMPLE);
        return queries.findByRootPath(rootPath);
    }
}
