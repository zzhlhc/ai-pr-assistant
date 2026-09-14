package com.zhouziheng.controller;

import com.zhouziheng.review.task.ReviewStats;
import com.zhouziheng.review.task.ReviewTaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 成本账。
 * <p>
 * 单独一个 /api/stats 而不是挂在 /api/tasks 下：任务相关的接口都以 /api/tasks/{id} 为主，
 * 挂一个 /api/tasks/stats 进去会跟路径变量抢路由，能跑但很脆。
 */
@RestController
@RequestMapping("/api/stats")
public class ReviewStatsController {

    private final ReviewTaskService taskService;

    public ReviewStatsController(ReviewTaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping
    public ReviewStats stats() {
        return taskService.stats();
    }
}
