package com.readcodeai.api;

import com.readcodeai.retrieve.FileContentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 读磁盘上的真实文件内容 —— 前端「证据卡片点开」用的接口。
 *
 * <p>这是产品可信度的最后一环：答案里的每条证据都是「文件 + 起止行」，
 * 点开必须能看见**那几行现在到底是什么**。所以这里读的是磁盘，不是索引里的副本；
 * 顺带返回「索引之后这个文件有没有被改过」，改过就提示行号可能漂移。
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileContentService fileContentService;

    public FileController(FileContentService fileContentService) {
        this.fileContentService = fileContentService;
    }

    @GetMapping("/content")
    public ApiResponse<FileContentService.FileContent> content(@RequestParam long repoId,
                                                               @RequestParam String path,
                                                               @RequestParam(required = false) Integer startLine,
                                                               @RequestParam(required = false) Integer endLine) {
        return ApiResponse.ok(fileContentService.read(repoId, path, startLine, endLine));
    }
}
