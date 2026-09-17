package com.readcodeai.api;

import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.RepoView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/repos")
public class RepoController {

    private final SymbolQueryService queryService;

    public RepoController(SymbolQueryService queryService) {
        this.queryService = queryService;
    }

    /** 已索引仓库列表，含解析成功率与调用解析率 —— 这两个数字直接反映索引质量。 */
    @GetMapping
    public ApiResponse<List<RepoView>> repos() {
        return ApiResponse.ok(queryService.repos());
    }
}
