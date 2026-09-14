package com.zhouziheng.controller;

import com.zhouziheng.review.task.ReviewTask;
import com.zhouziheng.review.task.ReviewTaskService;
import com.zhouziheng.review.task.TaskStatus;
import com.zhouziheng.review.task.TaskSummary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

@RestController
@RequestMapping("/api/tasks")
public class ReviewTaskController {

    private final ReviewTaskService taskService;

    public ReviewTaskController(ReviewTaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * 提交任务。
     * <p>
     * 未命中缓存：返回 202（任务"已接受"，在后台跑），前端跳到详情页看 SSE 进度。
     * 命中缓存：返回 200 + 一个已经做完的任务，请求进来就已经是终态了，没有"等待"这回事。
     * 两种情况的差别用状态码 + X-Cache 头明说，curl 和 DevTools 里一眼能看出命没命中，
     */
    @PostMapping
    public ResponseEntity<ReviewTask> submit(@RequestBody ReviewRequest request) {
        ReviewTask task = taskService.submit(request.repo(), request.commitSha());
        boolean hit = task.status() == TaskStatus.SUCCESS;
        return ResponseEntity.status(hit ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .header("X-Cache", hit ? "HIT" : "MISS")
                .body(task);
    }

    @GetMapping
    public List<TaskSummary> list() {
        return taskService.list();
    }

    @GetMapping("/{id}")
    public ReviewTask get(@PathVariable String id) {
        return taskService.get(id);
    }

    /**
     * SSE 推送任务全过程。浏览器用 EventSource 连上来，每变一次状态推一条事件，
     * 任务结束后服务端主动关闭连接。
     * 这里选 SSE 而不是 WebSocket：需求是纯服务端单向推送，
     * SSE 就是普通 HTTP，浏览器自带断线重连，也不用自己写心跳协议。
     */
    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ReviewTask>> stream(@PathVariable String id) {
        return taskService.stream(id).map(task -> ServerSentEvent.builder(task).event("task").build());
    }

    public record ReviewRequest(String repo, String commitSha) {
    }
}
