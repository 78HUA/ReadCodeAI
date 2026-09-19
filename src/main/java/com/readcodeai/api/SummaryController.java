package com.readcodeai.api;

import com.readcodeai.summary.RepoSummaryService;
import com.readcodeai.summary.model.RepoSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 结构化摘要接口 —— 前端「仓库概览」页的数据源。
 *
 * <p>返回里刻意把 {@code structure} 与 {@code semantics} 分成两块：
 * 前者是查库算出来的硬事实（每个数字、每个符号都能点开看代码），后者是模型写的一句话，
 * 而且**它提到的符号名已经回索引核对过**（核不到的会标在 {@code unverifiedSymbols} 里）。
 * 这个分区不是排版好看，而是要让使用者一眼看出哪些能信、哪些只是推测。
 */
@RestController
@RequestMapping("/api/summary")
public class SummaryController {

    private final RepoSummaryService summaryService;

    public SummaryController(RepoSummaryService summaryService) {
        this.summaryService = summaryService;
    }

    /**
     * @param semantics 要不要语义说明（关掉它就只返回查库算出来的结构，毫秒级）
     * @param refresh   强制重新生成语义说明、跳过一次缓存（界面上是「重新生成」按钮）
     */
    @GetMapping
    public ApiResponse<RepoSummary> summary(@RequestParam(required = false) Long repoId,
                                            @RequestParam(defaultValue = "true") boolean semantics,
                                            @RequestParam(defaultValue = "false") boolean refresh) {
        return ApiResponse.ok(summaryService.summarize(repoId, semantics, refresh));
    }

    /** 便于前端用 POST 传（语义说明默认开）。 */
    @PostMapping
    public ApiResponse<RepoSummary> summaryPost(@RequestBody SummaryRequest request) {
        return ApiResponse.ok(summaryService.summarize(
                request.repoId(),
                request.semantics() == null || request.semantics(),
                request.refresh() != null && request.refresh()));
    }

    public record SummaryRequest(Long repoId, Boolean semantics, Boolean refresh) {
    }
}
