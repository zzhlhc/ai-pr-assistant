package com.zhouziheng.review.task;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 任务列表用的轻量视图。
 * <p>
 * 列表页只需要"多少条问题、花了多少钱"，不需要把每个问题的正文都拉出来，
 * 所以单独定一个视图对象，一条 SQL 就能查完，不用去关联 review_issue。
 */
public record TaskSummary(String id,
                          String repo,
                          String commitSha,
                          TaskStatus status,
                          String stage,
                          String summary,
                          int issueCount,
                          BigDecimal cost,
                          Long elapsedMillis,
                          LocalDateTime createdAt,
                          LocalDateTime finishedAt) {
}
