package com.readcodeai.api;

import com.readcodeai.index.AsyncIndexer;
import com.readcodeai.index.model.IndexJob;
import com.readcodeai.index.store.IndexJobRepository;
import com.readcodeai.retrieve.NotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 索引任务的状态查询 —— 异步索引的"看进度"入口。
 *
 * <p>返回里带着 {@code stage / done / total / percent}：PARSING 阶段就是"已解析 N/M 个文件"。
 * 前端拿它做进度条；脚本可以拿它做"提交后等待完成"。
 */
@RestController
@RequestMapping("/api/index-jobs")
public class IndexJobController {

    private final IndexJobRepository jobs;
    private final AsyncIndexer asyncIndexer;

    public IndexJobController(IndexJobRepository jobs, AsyncIndexer asyncIndexer) {
        this.jobs = jobs;
        this.asyncIndexer = asyncIndexer;
    }

    @GetMapping("/{id}")
    public ApiResponse<IndexJob> job(@PathVariable long id) {
        return ApiResponse.ok(jobs.find(id)
                .orElseThrow(() -> new NotFoundException("没有这个索引任务：id=" + id)));
    }

    /** 队列与 worker 的当前状态（界面可以提示"前面还有 N 个任务在排队"）。 */
    @GetMapping("/queue")
    public ApiResponse<QueueState> queue() {
        return ApiResponse.ok(new QueueState(asyncIndexer.running(), asyncIndexer.queued()));
    }

    public record QueueState(int running, int queued) {
    }
}
