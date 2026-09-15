package com.zhouziheng.review.task;

import com.zhouziheng.review.ReviewOptions;
import com.zhouziheng.review.agent.AgentReviewer;
import com.zhouziheng.review.agent.AgentStep;
import com.zhouziheng.review.ReviewService;
import com.zhouziheng.review.model.ReviewResult;
import com.zhouziheng.review.prompt.ReviewPromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * 评审任务的编排层：提交任务、丢进线程池、把状态变化写进 store（进而推给 SSE）。
 * 大模型调用动辄一分钟以上，同步接口会把 HTTP 连接和 Tomcat 线程一直占着，
 * 所以改成「提交即返回、后台执行、状态推送」。
 */
@Service
public class ReviewTaskService {

    private static final Logger log = LoggerFactory.getLogger(ReviewTaskService.class);

    private final ReviewTaskStore store;
    private final ReviewTaskRepository repository;
    private final ReviewService reviewService;
    private final Executor executor;

    public ReviewTaskService(ReviewTaskStore store, ReviewTaskRepository repository, ReviewService reviewService,
                             @Qualifier("reviewExecutor") Executor executor) {
        this.store = store;
        this.repository = repository;
        this.reviewService = reviewService;
        this.executor = executor;
    }

    public ReviewTask submit(String repo, String commitSha) {
        return submit(repo, commitSha, ReviewOptions.DEFAULT);
    }

    /**
     * 提交一次评审。
     * <p>
     * 每次提交都真的评一遍，不做"同一个 commit + 同一组参数就复用历史结果"的短路：
     * 重复提交也重新调用模型，宁可多花一次钱。
     * <p>
     * 代价是连点两次、或同事打开同一个链接会各花一次钱；
     * 换来的是"提交一次 = 一次真实评审"，看到的一定是当下模型 + 当下提示词的结论，
     * 不会因为复用旧结果而让人误以为改了提示词/参数已经生效。
     */
    public ReviewTask submit(String repo, String commitSha, ReviewOptions options) {
        String signature = options.signature();
        // 两种策略的提示词是两套独立的东西，版本号各记各的
        String promptVersion = options.isAgent()
                ? AgentReviewer.PROMPT_VERSION
                : ReviewPromptBuilder.PROMPT_VERSION;

        ReviewTask task = ReviewTask.pending(UUID.randomUUID().toString().substring(0, 8),
                repo, commitSha, promptVersion);
        repository.insert(task, signature);
        store.save(task);
        executor.execute(() -> run(task, options));
        return task;
    }

    public ReviewTask get(String id) {
        return repository.findById(id);
    }

    public List<TaskSummary> list() {
        return repository.list();
    }

    /** 成本账：全部来自库里的聚合 */
    public ReviewStats stats() {
        return repository.stats();
    }

    public Flux<ReviewTask> stream(String id) {
        // 内存里没有这个任务（进程重启过，或者任务早就结束了），就推一次库里的终态然后收尾。
        // 否则 Sink 永远不会 complete，连接一直挂着，前端也永远等不到结束信号。
        if (store.get(id) == null) {
            ReviewTask persisted = repository.findById(id);
            return persisted == null ? Flux.empty() : Flux.just(persisted);
        }
        return store.stream(id);
    }

    private void run(ReviewTask task, ReviewOptions options) {
        long startMillis = System.currentTimeMillis();
        try {
            ReviewResult result = reviewService.review(task.repo(), task.commitSha(), options,
                    stage -> persist(latest(task).running(stage)));
            List<AgentStep> steps = result.trace() == null ? List.of() : result.trace().steps();
            ReviewTask finished = latest(task).success(result.report(), result.usage(),
                    result.elapsedMillis(), result.contexts(), steps);
            repository.replaceIssues(finished.id(), finished.report().issues());
            repository.replaceContexts(finished.id(), finished.contexts());
            repository.replaceSteps(finished.id(), steps);
            persist(finished);
            log.info("任务完成 | id={} | repo={} | commit={} | 召回文件={} | agent 轮数={} | 耗时={}ms",
                    task.id(), task.repo(), task.commitSha(), finished.contexts().size(),
                    steps.isEmpty() ? "-" : result.trace().rounds(), result.elapsedMillis());
        } catch (Exception e) {
            persist(latest(task).failed(e.getMessage(), System.currentTimeMillis() - startMillis));
            log.error("任务失败 | id={} | repo={} | commit={}", task.id(), task.repo(), task.commitSha(), e);
        }
    }

    /**
     * 一份状态写两个地方：内存负责 SSE 实时推送，MySQL 负责历史查询。
     * store 里只有"进行中"的任务才是热的，进程重启后靠库里的数据兜底。
     */
    private void persist(ReviewTask task) {
        store.save(task);
        repository.update(task);
    }

    /**
     * 状态流转必须基于"上一次落库的快照"，不能基于最初那个 PENDING 对象，
     * 否则 startedAt 这类字段会被后一次覆盖回 null。
     */
    private ReviewTask latest(ReviewTask task) {
        return store.get(task.id());
    }
}
