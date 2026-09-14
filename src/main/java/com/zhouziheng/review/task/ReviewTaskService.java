package com.zhouziheng.review.task;

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
import java.util.concurrent.atomic.AtomicLong;

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

    /**
     * 命中缓存的次数。缓存命中不会落库（直接复用历史结果），
     * 库里查不到痕迹，所以只能在进程内记一笔。
     */
    private final AtomicLong cacheHits = new AtomicLong();

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
     * 同一个 commit + 同一版提示词评审过就直接把老结果还回去 ——
     * 评审一次要几十秒、八分钱，重复提交同一个 commit 是最常见的浪费场景
     * （改完代码手抖点两次、分享链接被同事点一遍都会触发）。
     * <p>
     * 已知限制：两个人同一瞬间提交同一个 commit，会双双查不到缓存然后各跑一次。
     * 这里不修，是因为修它要么加唯一索引 + 抢锁，要么引 Redis 分布式锁，
     * 代价都比"极小概率多花八分钱"高。真要治，正确的位置是入库前的幂等键。
     */
    public ReviewTask submit(String repo, String commitSha) {
        ReviewTask cached = repository.findCached(repo, commitSha, ReviewPromptBuilder.PROMPT_VERSION);
        if (cached != null) {
            cacheHits.incrementAndGet();
            log.info("命中缓存 | repo={} | commit={} | 提示词={} | 复用任务={} | 原耗时={}ms",
                    repo, commitSha, cached.promptVersion(), cached.id(), cached.elapsedMillis());
            return cached;
        }

        ReviewTask task = ReviewTask.pending(UUID.randomUUID().toString().substring(0, 8),
                repo, commitSha, ReviewPromptBuilder.PROMPT_VERSION);
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

    /** 成本账：库里聚合出来的部分 + 内存里的缓存命中次数 */
    public ReviewStats stats() {
        return repository.stats().withCacheHits(cacheHits.get());
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
            ReviewResult result = reviewService.review(task.repo(), task.commitSha(),
                    stage -> persist(latest(task).running(stage)));
            ReviewTask finished = latest(task).success(result.report(), result.usage(), result.elapsedMillis());
            repository.replaceIssues(finished.id(), finished.report().issues());
            persist(finished);
            log.info("任务完成 | id={} | repo={} | commit={} | 耗时={}ms",
                    task.id(), task.repo(), task.commitSha(), result.elapsedMillis());
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
