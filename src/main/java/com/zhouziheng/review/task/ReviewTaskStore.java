package com.zhouziheng.review.task;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务的"热"状态：内存里的最新快照 + SSE 广播。
 * 历史查询走 ReviewTaskRepository（MySQL），这里只负责让在线的浏览器实时看到进展。
 * <p>
 * 每个任务配一个 replay().latest() 的 Sink：晚连上来的浏览器也能立刻收到最新状态。
 * 如果用普通热流，就得"先查一次快照、再订阅"，这两步之间产生的事件会丢。
 */
@Component
public class ReviewTaskStore {

    private final Map<String, ReviewTask> tasks = new ConcurrentHashMap<>();
    private final Map<String, Sinks.Many<ReviewTask>> streams = new ConcurrentHashMap<>();

    public void save(ReviewTask task) {
        tasks.put(task.id(), task);
        streamOf(task.id()).emitNext(task, Sinks.EmitFailureHandler.FAIL_FAST);
        if (task.status().isFinished()) {
            streamOf(task.id()).emitComplete(Sinks.EmitFailureHandler.FAIL_FAST);
        }
    }

    public ReviewTask get(String id) {
        return tasks.get(id);
    }

    public Flux<ReviewTask> stream(String id) {
        return streamOf(id).asFlux();
    }

    private Sinks.Many<ReviewTask> streamOf(String id) {
        return streams.computeIfAbsent(id, key -> Sinks.many().replay().latest());
    }
}
