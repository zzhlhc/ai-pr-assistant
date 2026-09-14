package com.zhouziheng.review.task;

import com.zhouziheng.review.model.ReviewReport;
import com.zhouziheng.review.model.TokenUsage;

import java.time.LocalDateTime;

/**
 * 评审任务的状态快照。
 * <p>
 * 刻意做成不可变对象：状态流转就是"造一个新快照再推事件"，
 * 不需要加锁，SSE 推给前端的每条事件也都是自洽的完整状态，
 * 前端不用自己维护增量。
 * <p>
 * promptVersion 是缓存的一部分：同一个 commit、同一版提示词，评审结果才允许复用。
 * 改了提示词却不升版本号，你会在"提示词迭代"的对比实验里拿到一份自己骗自己的数据。
 */
public record ReviewTask(String id,
                         String repo,
                         String commitSha,
                         String promptVersion,
                         TaskStatus status,
                         String stage,
                         ReviewReport report,
                         String error,
                         TokenUsage usage,
                         Long elapsedMillis,
                         LocalDateTime createdAt,
                         LocalDateTime startedAt,
                         LocalDateTime finishedAt) {

    public static ReviewTask pending(String id, String repo, String commitSha, String promptVersion) {
        return new ReviewTask(id, repo, commitSha, promptVersion, TaskStatus.PENDING, "排队中",
                null, null, null, null, LocalDateTime.now(), null, null);
    }

    /**
     * 推进阶段。startedAt 只在第一次进入 RUNNING 时记，
     * 否则后面的阶段更新会把真实开始时间冲掉。
     */
    public ReviewTask running(String stage) {
        return new ReviewTask(id, repo, commitSha, promptVersion, TaskStatus.RUNNING, stage,
                null, null, null, null, createdAt,
                startedAt == null ? LocalDateTime.now() : startedAt, null);
    }

    public ReviewTask success(ReviewReport report, TokenUsage usage, long elapsedMillis) {
        return new ReviewTask(id, repo, commitSha, promptVersion, TaskStatus.SUCCESS, "已完成",
                report, null, usage, elapsedMillis,
                createdAt, startedAt, LocalDateTime.now());
    }

    public ReviewTask failed(String error, long elapsedMillis) {
        return new ReviewTask(id, repo, commitSha, promptVersion, TaskStatus.FAILED, "已失败",
                null, error, null, elapsedMillis,
                createdAt, startedAt, LocalDateTime.now());
    }

    /** 从库里读出来只带了 summary，明细要单独查一次再补进来 */
    public ReviewTask withReport(ReviewReport report) {
        return new ReviewTask(id, repo, commitSha, promptVersion, status, stage, report, error, usage,
                elapsedMillis, createdAt, startedAt, finishedAt);
    }

    public int issueCount() {
        return report == null ? 0 : report.issues().size();
    }
}
