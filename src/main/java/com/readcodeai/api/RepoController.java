package com.readcodeai.api;

import com.readcodeai.index.IndexSummary;
import com.readcodeai.index.ProjectIndexer;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.RepoView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api/repos")
public class RepoController {

    private final SymbolQueryService queryService;
    private final ProjectIndexer indexer;

    public RepoController(SymbolQueryService queryService, ProjectIndexer indexer) {
        this.queryService = queryService;
        this.indexer = indexer;
    }

    /** 已索引仓库列表，含解析成功率与调用解析率 —— 这两个数字直接反映索引质量。 */
    @GetMapping
    public ApiResponse<List<RepoView>> repos() {
        return ApiResponse.ok(queryService.repos());
    }

    /**
     * 建立索引：给本地路径或 GitHub 链接。
     *
     * <p><b>同步执行</b>：小仓库几秒、中等的几十秒，请求会一直挂着。
     * 异步任务与进度上报留到前端那一步再做（那时才真正需要进度条）。
     */
    @PostMapping
    public ApiResponse<IndexSummary> index(@RequestBody IndexRequest request) {
        if (request.gitUrl() != null && !request.gitUrl().isBlank()) {
            return ApiResponse.ok(indexer.indexRemote(request.gitUrl()));
        }
        if (request.path() != null && !request.path().isBlank()) {
            return ApiResponse.ok(indexer.index(Path.of(request.path())));
        }
        throw new IllegalArgumentException("必须提供 path 或 gitUrl 之一");
    }

    /** 二选一：本地路径，或 GitHub 链接（公开仓库即可，私有仓库需配 READCODEAI_GITHUB_TOKEN）。 */
    public record IndexRequest(String path, String gitUrl) {
    }
}
