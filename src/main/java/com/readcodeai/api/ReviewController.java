package com.readcodeai.api;

import com.readcodeai.review.CodeReviewService;
import com.readcodeai.review.model.ReviewReport;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 代码审查接口：输入一个类，返回**带证据的审查意见**。
 *
 * <p>返回里两块分得很清楚：
 * <ul>
 *   <li>{@code machineFindings}：规则算出来的（方法过长、空 catch、没人调用、盲区集中…）——
 *       每条带位置，可复核，不经过模型</li>
 *   <li>{@code findings}：模型给的 —— 每条都必须带证据，且证据要过"磁盘对得上 + 在材料范围内"两关；
 *       {@code dropped} 与 {@code dropReasons} 如实报出被丢掉了多少条、为什么</li>
 * </ul>
 * 把丢掉的数量也返回去，是因为**审查工具最容易退化成"说得很像那么回事"**：
 * 这个数字就是它可信度的刻度。
 */
@RestController
@RequestMapping("/api/review")
public class ReviewController {

    private final CodeReviewService reviewService;

    public ReviewController(CodeReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @PostMapping
    public ApiResponse<ReviewReport> review(@RequestBody ReviewRequest request) {
        return ApiResponse.ok(reviewService.review(request.repoId(), request.target(), request.focus()));
    }

    /**
     * @param target 要审查的类：简单名、限定名或符号 id 都可以（只接受类型）
     * @param focus  可选：特别关注点（例如「异常处理」「并发」）
     */
    public record ReviewRequest(Long repoId, String target, String focus) {
    }
}
