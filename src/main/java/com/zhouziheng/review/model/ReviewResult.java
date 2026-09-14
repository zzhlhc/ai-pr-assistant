package com.zhouziheng.review.model;

/**
 * 一次评审的完整产出：报告 + 这次花了多少 token、多少钱、多久。
 * <p>
 * 只返回 ReviewReport 的话成本数据就丢了，而它正是 AI 应用最该被观测的指标。
 */
public record ReviewResult(ReviewReport report, TokenUsage usage, long elapsedMillis) {
}
