package com.readcodeai.api;

import com.readcodeai.index.IndexSummary;
import com.readcodeai.index.ProjectIndexer;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.RepoView;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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

    /**
     * 第三个入口：**上传压缩包**。
     *
     * <p>用来应付"拿不到服务器文件系统"的场景（部署在远端、或者用户手上只有一份 zip）。
     * 体积上限、路径校验、解压都在 {@link ProjectIndexer} 与 {@code ZipExtractor} 里，
     * 控制器只负责把字节流递过去。
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<IndexSummary> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传的文件是空的");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!name.endsWith(".zip")) {
            throw new IllegalArgumentException("只支持 .zip 压缩包（收到：" + file.getOriginalFilename() + "）");
        }
        try {
            return ApiResponse.ok(indexer.indexArchive(file.getInputStream(), file.getOriginalFilename()));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读取上传文件失败：" + e.getMessage(), e);
        }
    }

    /** 删除索引（子表随外键级联删除）。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Boolean> delete(@PathVariable long id) {
        return ApiResponse.ok(indexer.deleteIndex(id));
    }

    /** 二选一：本地路径，或 GitHub 链接（公开仓库即可，私有仓库需配 READCODEAI_GITHUB_TOKEN）。 */
    public record IndexRequest(String path, String gitUrl) {
    }
}
