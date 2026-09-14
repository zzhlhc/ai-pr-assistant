package com.zhouziheng.review.task;

import com.zhouziheng.review.agent.AgentStep;
import com.zhouziheng.review.context.RecalledFile;
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
 * promptVersion 是缓存的一部分：同一个 commit、同一版提示词，评审结果才允许复用。
 * 改了提示词却不升版本号，会拿到一份被旧结果污染的对比数据。
 * <p>
 * contexts 记录这次评审召回了哪些相关文件 —— 报告里每一条结论都建立在这些上下文之上，
 * 不记下来就没法解释"模型凭什么这么说"。
 * <p>
 * steps 是 agent 式那条链路的对应物：模型自己读了什么、按什么顺序读的、每一轮说了什么。
 * 两种策略的上下文证据留在同一个对象里，页面上就能把"我们替它挑的"和"它自己找的"并排看。
 */
public record ReviewTask(String id,
                         String repo,
                         String commitSha,
                         String promptVersion,
                         TaskStatus status,
                         String stage,
                         ReviewReport report,
                         List<RecalledFile> contexts,
                         List<AgentStep> steps,
                         String error,
                         TokenUsage usage,
                         Long elapsedMillis,
                         LocalDateTime createdAt,
                         LocalDateTime startedAt,
                         LocalDateTime finishedAt) {

    public static ReviewTask pending(String id, String repo, String commitSha, String promptVersion) {
        return new ReviewTask(id, repo, commitSha, promptVersion, TaskStatus.PENDING, "排队中",
                null, List.of(), List.of(), null, null, null, LocalDateTime.now(), null, null);
    }

    /**
     * 推进阶段。startedAt 只在第一次进入 RUNNING 时记，
     * 否则后面的阶段更新会把真实开始时间冲掉。
     */
    public ReviewTask running(String stage) {
        return new ReviewTask(id, repo, commitSha, promptVersion, TaskStatus.RUNNING, stage,
                null, contexts, steps, null, null, null, createdAt,
                startedAt == null ? LocalDateTime.now() : startedAt, null);
    }

    public ReviewTask success(ReviewReport report, TokenUsage usage, long elapsedMillis,
                              List<RecalledFile> contexts, List<AgentStep> steps) {
        return new ReviewTask(id, repo, commitSha, promptVersion, TaskStatus.SUCCESS, "已完成",
                report, contexts == null ? List.of() : contexts, steps == null ? List.of() : steps,
                null, usage, elapsedMillis, createdAt, startedAt, LocalDateTime.now());
    }

    public ReviewTask failed(String error, long elapsedMillis) {
        return new ReviewTask(id, repo, commitSha, promptVersion, TaskStatus.FAILED, "已失败",
                null, contexts, steps, error, null, elapsedMillis,
                createdAt, startedAt, LocalDateTime.now());
    }

    /** 从库里读出来只带了 summary，明细要单独查一次再补进来 */
    public ReviewTask withReport(ReviewReport report) {
        return new ReviewTask(id, repo, commitSha, promptVersion, status, stage, report, contexts, steps,
                error, usage, elapsedMillis, createdAt, startedAt, finishedAt);
    }

    /** 同理，召回明细和执行轨迹都是单独查一次再补进来 */
    public ReviewTask withContexts(List<RecalledFile> contexts) {
        return new ReviewTask(id, repo, commitSha, promptVersion, status, stage, report, contexts, steps,
                error, usage, elapsedMillis, createdAt, startedAt, finishedAt);
    }

    public ReviewTask withSteps(List<AgentStep> steps) {
        return new ReviewTask(id, repo, commitSha, promptVersion, status, stage, report, contexts, steps,
                error, usage, elapsedMillis, createdAt, startedAt, finishedAt);
    }

    public int issueCount() {
        return report == null ? 0 : report.issues().size();
    }
}
