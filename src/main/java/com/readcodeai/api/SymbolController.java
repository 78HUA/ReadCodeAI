package com.readcodeai.api;

import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.CallSiteView;
import com.readcodeai.retrieve.model.SymbolView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 第 1 层检索的 HTTP 接口 —— 全部是确定性查询，不依赖 LLM。
 *
 * <p>这些接口就是「不接模型也能用」的那部分能力，也是第 8 步前端要调的数据源。
 */
@RestController
@RequestMapping("/api/symbols")
public class SymbolController {

    private final SymbolQueryService queryService;

    public SymbolController(SymbolQueryService queryService) {
        this.queryService = queryService;
    }

    /** 定位：某功能/符号在哪定义。 */
    @GetMapping("/locate")
    public ApiResponse<List<SymbolView>> locate(@RequestParam(required = false) Long repoId,
                                                @RequestParam String name,
                                                @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(queryService.locate(repoId, name, limit));
    }

    @GetMapping("/{id}")
    public ApiResponse<SymbolView> symbol(@PathVariable long id) {
        return ApiResponse.ok(queryService.requireSymbol(id));
    }

    /** 谁调用了它。 */
    @GetMapping("/{id}/callers")
    public ApiResponse<List<CallSiteView>> callers(@PathVariable long id) {
        return ApiResponse.ok(queryService.callers(id));
    }

    /** 它调用了谁。 */
    @GetMapping("/{id}/callees")
    public ApiResponse<List<CallSiteView>> callees(@PathVariable long id) {
        return ApiResponse.ok(queryService.callees(id));
    }

    /** 这个接口有哪些实现类（直接关系）。 */
    @GetMapping("/{id}/implementations")
    public ApiResponse<List<SymbolView>> implementations(@PathVariable long id) {
        return ApiResponse.ok(queryService.implementations(id));
    }
}
