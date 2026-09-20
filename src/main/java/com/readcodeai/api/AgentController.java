package com.readcodeai.api;

import com.readcodeai.agent.AgentService;
import com.readcodeai.agent.model.AgentAnswer;
import com.readcodeai.agent.model.AgentMode;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 问答接口：**多跳与单跳走同一个入口，靠 {@code mode} 切换**。
 *
 * <p>两个模式共用入口是刻意的 —— 对比实验要求"同一批问题、两种跑法、同一套指标"，
 * 分成两个接口就很容易在两处慢慢写歪，最后比出来的差异说明不了问题。
 *
 * <p>响应里多了 {@code steps}（每一跳查了什么、查到什么、证据是什么）与 {@code stopReason}。
 * 这两个字段是这个接口与 {@code /api/ask} 的本质区别：**过程可见**。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping
    public ApiResponse<AgentAnswer> ask(@RequestBody AgentRequest request) {
        return ApiResponse.ok(agentService.ask(request.repoId(), request.question(),
                AgentMode.parse(request.mode()), request.scopePath(), request.topK(),
                Boolean.TRUE.equals(request.deep())));
    }

    /**
     * @param mode {@code multi}（默认）= 模型自主多跳；{@code single} = 单跳基线（对比实验用）；
     *             {@code summary} = 总结类问题（一般由系统**自动**判定，见 {@code SummaryIntent}）
     * @param deep 深链模式：把多跳的轮次/时长/token 额度换大（额度在 {@code readcodeai.llm.deep} 配，
     *             前端只能选要不要 —— 预算是闸门）。只影响多跳：确定性问题与总结类问题都不经过它
     */
    public record AgentRequest(Long repoId, String question, String mode, String scopePath, Integer topK,
                               Boolean deep) {
    }
}
