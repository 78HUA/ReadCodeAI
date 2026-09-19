package com.readcodeai.api;

import com.readcodeai.index.AsyncIndexer;
import com.readcodeai.index.IndexSummary;
import com.readcodeai.index.ProjectIndexer;
import com.readcodeai.index.model.IndexJob;
import com.readcodeai.index.store.IndexJobRepository;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.RepoView;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import com.readcodeai.config.ReadCodeAiProperties;
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
    private final AsyncIndexer asyncIndexer;
    private final IndexJobRepository jobs;
    private final ReadCodeAiProperties properties;

    public RepoController(SymbolQueryService queryService, ProjectIndexer indexer,
                          AsyncIndexer asyncIndexer, IndexJobRepository jobs,
                          ReadCodeAiProperties properties) {
        this.queryService = queryService;
        this.indexer = indexer;
        this.asyncIndexer = asyncIndexer;
        this.jobs = jobs;
        this.properties = properties;
    }

    /** 已索引仓库列表，含解析成功率与调用解析率 —— 这两个数字直接反映索引质量。 */
    @GetMapping
    public ApiResponse<List<RepoView>> repos() {
        return ApiResponse.ok(queryService.repos());
    }

    /**
     * 提交索引任务：给本地路径或 GitHub 链接。
     *
     * <p><b>异步</b>：立刻返回一个任务（含 jobId），干活在后台，进度查
     * {@code GET /api/index-jobs/{jobId}}。原来这里是同步的 ——
     * 一次请求挂十几秒到几分钟，前端只能干等、网关一超时就断（见验证记录里的实测）。
     *
     * <p>队列满了会明确拒绝（"请稍后再提交"），而不是无限堆积。
     */
    @PostMapping
    public ApiResponse<IndexJob> index(@RequestBody IndexRequest request) {
        if (request.gitUrl() != null && !request.gitUrl().isBlank()) {
            return ApiResponse.ok(asyncIndexer.submitRemote(request.gitUrl().trim()));
        }
        if (request.path() != null && !request.path().isBlank()) {
            return ApiResponse.ok(asyncIndexer.submitLocal(Path.of(request.path().trim())));
        }
        throw new IllegalArgumentException("必须提供 path 或 gitUrl 之一");
    }

    /** 单个仓库的状态：仓库行本身 + **最近一次索引任务的进度**（界面轮询用它）。 */
    @GetMapping("/{id}")
    public ApiResponse<RepoStatus> repo(@PathVariable long id) {
        RepoView repo = queryService.requireRepo(id);
        return ApiResponse.ok(new RepoStatus(repo, jobs.latestForRepo(id).orElse(null)));
    }

    /** @param progress 还没跑过任务或任务记录已删时为 null */
    public record RepoStatus(RepoView repo, IndexJob progress) {
    }

    /**
     * 第三个入口：**上传压缩包**（同样异步）。
     *
     * <p>控制器只做"把字节流落到临时文件"这一件快事（十几 MB 是毫秒级），
     * 解压与索引都交给后台任务 —— 否则解压一个大压缩包同样会把请求挂住。
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<IndexJob> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传的文件是空的");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!name.endsWith(".zip")) {
            throw new IllegalArgumentException("只支持 .zip 压缩包（收到：" + file.getOriginalFilename() + "）");
        }
        java.nio.file.Path archive = null;
        try {
            java.nio.file.Path workspace = java.nio.file.Path.of(
                    properties.getIndex().getWorkspace()).resolve("uploads");
            java.nio.file.Files.createDirectories(workspace);
            archive = java.nio.file.Files.createTempFile(workspace, "incoming-", ".zip");
            try (var in = file.getInputStream()) {
                java.nio.file.Files.copy(in, archive, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return ApiResponse.ok(asyncIndexer.submitArchive(archive, file.getOriginalFilename()));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("保存上传文件失败：" + e.getMessage(), e);
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
