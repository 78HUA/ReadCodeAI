package com.readcodeai.api;

import com.readcodeai.agent.log.AnswerLogService;
import com.readcodeai.agent.log.AnswerLogStats;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行统计：**这个服务被问了多少次、花了多少、拒答了多少**（只读）。
 *
 * <h3>它与「自动评估」是两码事，页面必须分开说</h3>
 * 自动评估（{@code /api/eval/run}）是**机器给自己打的卷**：题目自动生成、答案由静态分析算出、
 * 判卷也是程序做的，测的是管线自洽。而这里的数字来自**真实问答的流水**
 * （{@code answer_log}：每次问答一行，含路线、token、耗时、拒答与证据核验结果）——
 * 它回答的是"这套东西真被人用起来，账是什么样"。
 *
 * <h3>口径（跟着数字一起显示，别让数字自己说话）</h3>
 * <ul>
 *   <li>统计范围 = **库里现存的问答流水**（删仓库会级联删掉它的问答记录）</li>
 *   <li>{@code cost} 按配置单价估算；免费档单价为 0 时恒为 0（机制在，但管不住 0 成本）</li>
 *   <li>命中缓存的那次**没有真的再花一遍** token，所以它单列在 {@code savedTokens} 里</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final AnswerLogService answerLogService;

    public MetricsController(AnswerLogService answerLogService) {
        this.answerLogService = answerLogService;
    }

    /** @param repoId 可选：只看某个仓库；不传 = 全部仓库 */
    @GetMapping
    public ApiResponse<AnswerLogStats> metrics(@RequestParam(required = false) Long repoId) {
        return ApiResponse.ok(answerLogService.stats(repoId));
    }
}
