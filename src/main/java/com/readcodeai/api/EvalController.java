package com.readcodeai.api;

import com.readcodeai.eval.EvalRunner;
import com.readcodeai.retrieve.SymbolQueryService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 跑评估集 —— 前端「指标」页用的接口。
 *
 * <p>为什么敢在前端一键跑：这套评估**答案由静态分析算出、不调模型**，
 * 所以 200 道题只要几秒、不花一分钱 token、同一 seed 完全可复现。
 * 一个"点一下就能看到命中率"的按钮，比文档里写一段数字更有说服力。
 *
 * <p>（同时也是诚实的：这些题都是确定性问题，测的是管线自洽，**不是**开放问答的准确率 ——
 * 这一点在返回里也带着，见 {@code EvalRunner.Report} 的注释。）
 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final EvalRunner evalRunner;
    private final SymbolQueryService queryService;

    public EvalController(EvalRunner evalRunner, SymbolQueryService queryService) {
        this.evalRunner = evalRunner;
        this.queryService = queryService;
    }

    @PostMapping("/run")
    public ApiResponse<EvalRunner.Report> run(@RequestBody EvalRequest request) {
        long repoId = request.repoId() != null ? request.repoId() : queryService.requireLatestRepoId();
        long seed = request.seed() == null ? 20260918L : request.seed();
        int perType = request.perType() == null ? 20 : Math.min(Math.max(request.perType(), 1), 200);
        return ApiResponse.ok(evalRunner.run(repoId, seed, perType));
    }

    /**
     * @param perType 每种题型出多少道（5 种题型，总数约为它的 5 倍）
     */
    public record EvalRequest(Long repoId, Long seed, Integer perType) {
    }
}
