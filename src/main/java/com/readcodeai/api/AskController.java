package com.readcodeai.api;

import com.readcodeai.agent.AnswerService;
import com.readcodeai.agent.model.AskAnswer;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 问答接口。这是「模型参与」的唯一入口 ——
 * 其余接口（定位 / 调用关系 / 实现类 / 全文检索）都是确定性的，不需要模型。
 */
@RestController
@RequestMapping("/api/ask")
public class AskController {

    private final AnswerService answerService;

    public AskController(AnswerService answerService) {
        this.answerService = answerService;
    }

    @PostMapping
    public ApiResponse<AskAnswer> ask(@RequestBody AskRequest request) {
        return ApiResponse.ok(answerService.ask(
                request.repoId(), request.question(), request.scopePath(), request.topK()));
    }

    /** {@code scopePath} 可选：把范围限制在某个文件或目录（第 2 步先支持单文件问答）。 */
    public record AskRequest(Long repoId, String question, String scopePath, Integer topK) {
    }
}
