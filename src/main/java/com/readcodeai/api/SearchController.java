package com.readcodeai.api;

import com.readcodeai.retrieve.TextRetriever;
import com.readcodeai.retrieve.model.ChunkHit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 第 2 层检索的 HTTP 接口（全文检索）。
 *
 * <p>与 {@code /api/symbols/*} 的区别：那组是**查表/图查询**（答案是确定的），
 * 这组是**按词找位置**（答案是候选集，需要人来判断哪个才是要的）。
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final TextRetriever textRetriever;

    public SearchController(TextRetriever textRetriever) {
        this.textRetriever = textRetriever;
    }

    /** 按标识符 / 注释里的词找代码块，返回带「文件 + 行号」的候选。 */
    @GetMapping
    public ApiResponse<List<ChunkHit>> search(@RequestParam(required = false) Long repoId,
                                              @RequestParam("q") String query,
                                              @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(textRetriever.search(repoId, query, limit));
    }
}
