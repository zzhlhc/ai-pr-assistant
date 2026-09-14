package com.zhouziheng.review.model;

import com.zhouziheng.review.agent.AgentTrace;
import com.zhouziheng.review.context.RecalledFile;

import java.util.List;

/**
 * 一次评审的完整产出：报告 + 这次花了多少 token、多少钱、多久 + 用了哪些上下文。
 * <p>
 * 只返回 ReviewReport 的话成本数据和上下文来源就丢了，
 * 而后者恰恰是回答"这次评审凭什么得出这个结论"的依据。
 * <p>
 * contexts 和 trace 是两种策略各自的"上下文证据"，永远只有一个有值：
 * 预塞式给 contexts（我们挑出来的文件），agent 式给 trace（模型自己读的过程）。
 * 把它们都留在同一个模型里，页面上就能把两种策略的执行过程对照着看。
 */
public record ReviewResult(ReviewReport report,
                           TokenUsage usage,
                           long elapsedMillis,
                           List<RecalledFile> contexts,
                           AgentTrace trace) {

    /**
     * 模型调用这一层只负责产出报告和用量，上下文是它上游的事，
     * 所以给它留一个不带上下文的入口，由编排层拿到结果后再补全。
     */
    public ReviewResult(ReviewReport report, TokenUsage usage, long elapsedMillis) {
        this(report, usage, elapsedMillis, List.of(), null);
    }

    public ReviewResult(ReviewReport report, TokenUsage usage, long elapsedMillis, List<RecalledFile> contexts) {
        this(report, usage, elapsedMillis, contexts, null);
    }
}
