package com.zhouziheng.review.model;

import com.zhouziheng.review.context.RecalledFile;

import java.util.List;

/**
 * 一次评审的完整产出：报告 + 这次花了多少 token、多少钱、多久 + 用了哪些上下文。
 * <p>
 * 只返回 ReviewReport 的话成本数据和召回情况就丢了，
 * 而后者恰恰是回答"这次评审凭什么得出这个结论"的依据。
 */
public record ReviewResult(ReviewReport report,
                           TokenUsage usage,
                           long elapsedMillis,
                           List<RecalledFile> contexts) {

    /**
     * 模型调用这一层只负责产出报告和用量，召回结果是它上游的事，
     * 所以给它留一个不带 contexts 的入口，由编排层拿到结果后再补全。
     */
    public ReviewResult(ReviewReport report, TokenUsage usage, long elapsedMillis) {
        this(report, usage, elapsedMillis, List.of());
    }
}
