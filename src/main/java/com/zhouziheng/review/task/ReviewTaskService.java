package com.zhouziheng.review.task;

import com.zhouziheng.review.ReviewProgress;
import com.zhouziheng.review.agent.AgentReviewer;
import com.zhouziheng.review.agent.AgentStep;
import com.zhouziheng.review.ReviewService;
import com.zhouziheng.review.model.ReviewResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
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

    /**
     * 提交一次评审。
     * <p>
     * 每次提交都真的评一遍，不做"同一个 commit 就复用历史结果"的短路：
     * 重复提交也重新调用模型，宁可多花一次钱。
     * <p>
     * 代价是连点两次、或同事打开同一个链接会各花一次钱；
     * 换来的是"提交一次 = 一次真实评审"，看到的一定是当下模型 + 当下提示词的结论，
     * 不会因为复用旧结果而让人误以为改了提示词已经生效。
     */
    public ReviewTask submit(String repo, String commitSha) {
        ReviewTask task = ReviewTask.pending(UUID.randomUUID().toString().substring(0, 8),
                repo, commitSha, AgentReviewer.PROMPT_VERSION);
        repository.insert(task);
        store.save(task);
        executor.execute(() -> run(task));
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

    private void run(ReviewTask task) {
        long startMillis = System.currentTimeMillis();
        try {
            ReviewResult result = reviewService.review(task.repo(), task.commitSha(), progressOf(task));
            List<AgentStep> steps = result.trace() == null ? List.of() : result.trace().steps();
            ReviewTask finished = latest(task).success(result.report(), result.usage(),
                    result.elapsedMillis(), steps);
            repository.replaceIssues(finished.id(), finished.report().issues());
            repository.replaceSteps(finished.id(), steps);
            persist(finished);
            log.info("任务完成 | id={} | repo={} | commit={} | agent 轮数={} | 耗时={}ms",
                    task.id(), task.repo(), task.commitSha(),
                    steps.isEmpty() ? "-" : result.trace().rounds(), result.elapsedMillis());
        } catch (Exception e) {
            ReviewTask failed = latest(task).failed(e.getMessage(), System.currentTimeMillis() - startMillis);
            // 失败前跑过的那几步也落库：SSE 推过的轨迹，刷新页面后不该消失
            repository.replaceSteps(failed.id(), failed.steps());
            persist(failed);
            log.error("任务失败 | id={} | repo={} | commit={}", task.id(), task.repo(), task.commitSha(), e);
        }
    }

    /**
     * 把评审过程中的进度映射成任务快照。两种粒度的落点刻意不一样：
     * <ul>
     *   <li>阶段文案写内存 + 写库，刷新页面也还知道"跑到哪一步了"；</li>
     *   <li>执行轨迹只写内存 —— 轨迹明细本来就不在 review_task 表里（收尾时整体 replaceSteps），
     *       每跑一步都 UPDATE 一次库纯属浪费。SSE 推的就是内存快照，页面照样实时看得到。</li>
     * </ul>
     * 累积而不是覆盖是关键：只推 stage 那一个字符串的话，后一步会把前一步顶掉，
     * 用户看到的永远只有最新那一句，整个过程就丢了。
     */
    private ReviewProgress progressOf(ReviewTask task) {
        List<AgentStep> collected = new ArrayList<>();
        return new ReviewProgress() {
            @Override
            public void stage(String text) {
                persist(latest(task).running(text, List.copyOf(collected)));
            }

            @Override
            public void step(AgentStep step) {
                collected.add(step);
                ReviewTask current = latest(task);
                store.save(current.running(current.stage(), List.copyOf(collected)));
            }
        };
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
