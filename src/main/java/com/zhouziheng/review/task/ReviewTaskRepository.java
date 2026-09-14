package com.zhouziheng.review.task;

import com.zhouziheng.review.context.RecalledFile;
import com.zhouziheng.review.model.ReviewIssue;
import com.zhouziheng.review.model.ReviewReport;
import com.zhouziheng.review.model.Severity;
import com.zhouziheng.review.model.TokenUsage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 任务的持久化。
 * <p>
 * 用的是 Spring 自带的 JdbcClient，没有引入 MyBatis-Plus ——
 * 它目前只有 spring-boot3-starter，为了一个 ORM 把主框架降到 3.x 不划算；
 * 几张表、几条固定 SQL 也用不上 ORM 的动态能力。
 * <p>
 * 这里顺带承担了"评审结果缓存"的职责：评审是 repo + commitSha 的纯函数，
 * 天然幂等，不是热数据（一个 commit 通常只评一次），所以没必要再引一个 Redis，
 * 直接拿 review_task 表当缓存，用 prompt_version + rag_signature 控制失效。
 */
@Repository
public class ReviewTaskRepository {

    private final JdbcClient jdbc;

    public ReviewTaskRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ReviewTask task, String ragSignature) {
        jdbc.sql("""
                        INSERT INTO review_task (id, repo, commit_sha, prompt_version, rag_signature,
                                                 status, stage, issue_count, created_at)
                        VALUES (:id, :repo, :commitSha, :promptVersion, :ragSignature,
                                :status, :stage, 0, :createdAt)
                        """)
                .param("id", task.id())
                .param("repo", task.repo())
                .param("commitSha", task.commitSha())
                .param("promptVersion", task.promptVersion())
                .param("ragSignature", ragSignature)
                .param("status", task.status().name())
                .param("stage", task.stage())
                .param("createdAt", task.createdAt())
                .update();
    }

    public void update(ReviewTask task) {
        TokenUsage usage = task.usage();
        jdbc.sql("""
                        UPDATE review_task
                           SET status = :status,
                               stage = :stage,
                               summary = :summary,
                               error = :error,
                               issue_count = :issueCount,
                               prompt_tokens = :promptTokens,
                               completion_tokens = :completionTokens,
                               total_tokens = :totalTokens,
                               cached_tokens = :cachedTokens,
                               cost = :cost,
                               elapsed_ms = :elapsedMillis,
                               started_at = :startedAt,
                               finished_at = :finishedAt
                         WHERE id = :id
                        """)
                .param("status", task.status().name())
                .param("stage", task.stage())
                .param("summary", summaryOf(task))
                .param("error", task.error())
                .param("issueCount", task.issueCount())
                .param("promptTokens", usage == null ? null : usage.promptTokens())
                .param("completionTokens", usage == null ? null : usage.completionTokens())
                .param("totalTokens", usage == null ? null : usage.totalTokens())
                .param("cachedTokens", usage == null ? null : usage.cachedTokens())
                .param("cost", usage == null ? null : usage.cost())
                .param("elapsedMillis", task.elapsedMillis())
                .param("startedAt", task.startedAt())
                .param("finishedAt", task.finishedAt())
                .param("id", task.id())
                .update();
    }

    /**
     * 任务只执行一次，写之前先清一遍，重复触发也不会攒出两倍的问题。
     */
    public void replaceIssues(String taskId, List<ReviewIssue> issues) {
        jdbc.sql("DELETE FROM review_issue WHERE task_id = :taskId").param("taskId", taskId).update();
        for (ReviewIssue issue : issues) {
            jdbc.sql("""
                            INSERT INTO review_issue (task_id, severity, category, file, line_no, issue, suggestion, evidence)
                            VALUES (:taskId, :severity, :category, :file, :lineNo, :issue, :suggestion, :evidence)
                            """)
                    .param("taskId", taskId)
                    .param("severity", issue.severity().name())
                    .param("category", issue.category())
                    .param("file", issue.file())
                    .param("lineNo", issue.line())
                    .param("issue", issue.issue())
                    .param("suggestion", issue.suggestion())
                    .param("evidence", issue.evidence())
                    .update();
        }
    }

    /** 这次评审召回了哪些相关代码，明细同样单独一张表 */
    public void replaceContexts(String taskId, List<RecalledFile> contexts) {
        jdbc.sql("DELETE FROM review_task_context WHERE task_id = :taskId").param("taskId", taskId).update();
        for (RecalledFile context : contexts) {
            jdbc.sql("""
                            INSERT INTO review_task_context (task_id, path, skeleton_chars, raw_chars)
                            VALUES (:taskId, :path, :chars, :rawChars)
                            """)
                    .param("taskId", taskId)
                    .param("path", context.path())
                    .param("chars", context.chars())
                    .param("rawChars", context.rawChars())
                    .update();
        }
    }

    public List<TaskSummary> list() {
        return jdbc.sql("""
                        SELECT id, repo, commit_sha, status, stage, summary, issue_count, cost, elapsed_ms,
                               created_at, finished_at
                          FROM review_task
                         ORDER BY created_at DESC
                         LIMIT 100
                        """)
                .query((rs, rowNum) -> new TaskSummary(
                        rs.getString("id"),
                        rs.getString("repo"),
                        rs.getString("commit_sha"),
                        TaskStatus.valueOf(rs.getString("status")),
                        rs.getString("stage"),
                        rs.getString("summary"),
                        rs.getInt("issue_count"),
                        rs.getBigDecimal("cost"),
                        rs.getObject("elapsed_ms", Long.class),
                        rs.getObject("created_at", java.time.LocalDateTime.class),
                        rs.getObject("finished_at", java.time.LocalDateTime.class)))
                .list();
    }

    /**
     * 缓存查询：同一个 commit + 同一版提示词 + 同一组召回参数，只要成功评过一次就直接复用。
     * <p>
     * 注意这里只认 SUCCESS。FAILED / PENDING / RUNNING 都不算数 ——
     * 失败的结果可能是网络抖动或限流造成的，缓存下来等于把一次偶发故障永久固化。
     */
    public ReviewTask findCached(String repo, String commitSha, String promptVersion, String ragSignature) {
        ReviewTask task = jdbc.sql("""
                        SELECT *
                          FROM review_task
                         WHERE repo = :repo
                           AND commit_sha = :commitSha
                           AND prompt_version = :promptVersion
                           AND rag_signature = :ragSignature
                           AND status = 'SUCCESS'
                         ORDER BY finished_at DESC
                         LIMIT 1
                        """)
                .param("repo", repo)
                .param("commitSha", commitSha)
                .param("promptVersion", promptVersion)
                .param("ragSignature", ragSignature)
                .query(this::mapTask)
                .optional()
                .orElse(null);
        return task == null ? null : withDetails(task);
    }

    /**
     * 详情：任务本身 + 问题明细 + 召回明细，三条 SQL。
     * 刻意不 JOIN —— 两张明细都是多行，JOIN 出来还要在内存里按 task 分组去重，反而更绕。
     */
    public ReviewTask findById(String id) {
        ReviewTask task = jdbc.sql("SELECT * FROM review_task WHERE id = :id")
                .param("id", id)
                .query(this::mapTask)
                .optional()
                .orElse(null);
        return task == null ? null : withDetails(task);
    }

    /**
     * 成本账。两条不带 JOIN 的聚合查询：
     * 任务表算钱和耗时，问题表单独 count。
     */
    public ReviewStats stats() {
        long issueCount = jdbc.sql("SELECT COUNT(*) FROM review_issue").query(Long.class).single();

        return jdbc.sql("""
                        SELECT COUNT(*)                                            AS task_count,
                               COALESCE(SUM(status = 'SUCCESS'), 0)                AS success_count,
                               COALESCE(SUM(total_tokens), 0)                      AS total_tokens,
                               COALESCE(SUM(cost), 0)                              AS total_cost,
                               AVG(CASE WHEN status = 'SUCCESS' THEN elapsed_ms END) AS avg_elapsed_ms
                          FROM review_task
                        """)
                .query((rs, rowNum) -> new ReviewStats(
                        rs.getLong("task_count"),
                        rs.getLong("success_count"),
                        issueCount,
                        rs.getLong("total_tokens"),
                        rs.getBigDecimal("total_cost"),
                        rs.getObject("avg_elapsed_ms", Long.class),
                        0))
                .single();
    }

    private ReviewTask withDetails(ReviewTask task) {
        return task.withReport(new ReviewReport(task.report().summary(), findIssues(task.id())))
                .withContexts(findContexts(task.id()));
    }

    private List<ReviewIssue> findIssues(String taskId) {
        return jdbc.sql("""
                        SELECT severity, category, file, line_no, issue, suggestion, evidence
                          FROM review_issue
                         WHERE task_id = :taskId
                         ORDER BY id
                        """)
                .param("taskId", taskId)
                .query((rs, rowNum) -> new ReviewIssue(
                        Severity.valueOf(rs.getString("severity")),
                        rs.getString("category"),
                        rs.getString("file"),
                        rs.getInt("line_no"),
                        rs.getString("issue"),
                        rs.getString("suggestion"),
                        rs.getString("evidence")))
                .list();
    }

    private List<RecalledFile> findContexts(String taskId) {
        return jdbc.sql("""
                        SELECT path, skeleton_chars, raw_chars
                          FROM review_task_context
                         WHERE task_id = :taskId
                         ORDER BY id
                        """)
                .param("taskId", taskId)
                .query((rs, rowNum) -> new RecalledFile(
                        rs.getString("path"),
                        rs.getInt("skeleton_chars"),
                        rs.getInt("raw_chars")))
                .list();
    }

    private ReviewTask mapTask(ResultSet rs, int rowNum) throws SQLException {
        Integer promptTokens = rs.getObject("prompt_tokens", Integer.class);
        TokenUsage usage = promptTokens == null ? null : new TokenUsage(
                promptTokens,
                rs.getObject("completion_tokens", Integer.class),
                rs.getObject("total_tokens", Integer.class),
                rs.getObject("cached_tokens", Long.class),
                rs.getBigDecimal("cost"));

        return new ReviewTask(
                rs.getString("id"),
                rs.getString("repo"),
                rs.getString("commit_sha"),
                rs.getString("prompt_version"),
                TaskStatus.valueOf(rs.getString("status")),
                rs.getString("stage"),
                // 明细单独查，先放个只有 summary 的空壳
                new ReviewReport(rs.getString("summary"), List.of()),
                List.of(),
                rs.getString("error"),
                usage,
                rs.getObject("elapsed_ms", Long.class),
                rs.getObject("created_at", java.time.LocalDateTime.class),
                rs.getObject("started_at", java.time.LocalDateTime.class),
                rs.getObject("finished_at", java.time.LocalDateTime.class));
    }

    private String summaryOf(ReviewTask task) {
        return task.report() == null ? null : task.report().summary();
    }
}
