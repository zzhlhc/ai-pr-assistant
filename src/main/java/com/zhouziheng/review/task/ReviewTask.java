package com.zhouziheng.review.task;

import com.zhouziheng.review.agent.AgentStep;
import com.zhouziheng.review.model.ReviewReport;
import com.zhouziheng.review.model.TokenUsage;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 评审任务的状态快照。
 * <p>
 * 刻意做成不可变对象：状态流转就是"造一个新快照再推事件"，
 * 不需要加锁，SSE 推给前端的每条事件也都是自洽的完整状态，
 * 前端不用自己维护增量。
 * <p>
 * promptVersion 记的是这行结果出自哪一版提示词。现在没有"同 commit 复用历史结果"的逻辑，
 * 它纯粹是留痕：没有这个字段，两份结果摆在一起就分不清是提示词变了还是模型抽风了。
 * <p>
 * steps 记录模型自己读了什么、按什么顺序读的、每一轮说了什么 ——
 * 报告里每一条结论都建立在这些上下文之上，不记下来就没法解释"模型凭什么这么说"。
 */
public record ReviewTask(String id,
                         String repo,
                         String repoName,
                         String commitSha,
                         String promptVersion,
                         TaskStatus status,
                         String stage,
                         ReviewReport report,
                         List<AgentStep> steps,
                         String error,
                         TokenUsage usage,
                         Long elapsedMillis,
                         LocalDateTime createdAt,
                         LocalDateTime startedAt,
                         LocalDateTime finishedAt) {

    public static ReviewTask pending(String id, String repo, String repoName, String commitSha,
                                     String promptVersion) {
        return new ReviewTask(id, repo, repoName, commitSha, promptVersion, TaskStatus.PENDING, "排队中",
                null, List.of(), null, null, null, LocalDateTime.now(), null, null);
    }

    /**
     * 推进阶段。startedAt 只在第一次进入 RUNNING 时记，
     * 否则后面的阶段更新会把真实开始时间冲掉。
     * <p>
     * steps 传的是"到这里为止已经跑完的步骤"（不是增量）。
     * 模型每完成一步就推一次，页面才能实时累积出完整轨迹 ——
     * 只靠 stage 那一个字符串的话，后一步会把前一步顶掉，过程就没了。
     */
    public ReviewTask running(String stage, List<AgentStep> steps) {
        return new ReviewTask(id, repo, repoName, commitSha, promptVersion, TaskStatus.RUNNING, stage,
                null, steps == null ? List.of() : steps, null, null, null, createdAt,
                startedAt == null ? LocalDateTime.now() : startedAt, null);
    }

    public ReviewTask success(ReviewReport report, TokenUsage usage, long elapsedMillis,
                              List<AgentStep> steps) {
        return new ReviewTask(id, repo, repoName, commitSha, promptVersion, TaskStatus.SUCCESS, "已完成",
                report, steps == null ? List.of() : steps,
                null, usage, elapsedMillis, createdAt, startedAt, LocalDateTime.now());
    }

    public ReviewTask failed(String error, long elapsedMillis) {
        return new ReviewTask(id, repo, repoName, commitSha, promptVersion, TaskStatus.FAILED, "已失败",
                null, steps, error, null, elapsedMillis,
                createdAt, startedAt, LocalDateTime.now());
    }

    /** 从库里读出来只带了 summary，明细要单独查一次再补进来 */
    public ReviewTask withReport(ReviewReport report) {
        return new ReviewTask(id, repo, repoName, commitSha, promptVersion, status, stage, report, steps,
                error, usage, elapsedMillis, createdAt, startedAt, finishedAt);
    }

    /** 同理，执行轨迹是单独查一次再补进来 */
    public ReviewTask withSteps(List<AgentStep> steps) {
        return new ReviewTask(id, repo, repoName, commitSha, promptVersion, status, stage, report, steps,
                error, usage, elapsedMillis, createdAt, startedAt, finishedAt);
    }

    public int issueCount() {
        return report == null ? 0 : report.issues().size();
    }
}
