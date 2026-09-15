package com.zhouziheng.review.task;

import java.math.BigDecimal;

/**
 * 成本账：单次评审用了多少 token、花了多少钱，累计下来是多少。
 * 全部来自 review_task 表的一次 SQL 聚合 —— 每次提交都会落一行，没有"复用历史结果"这种不留痕迹的路径。
 */
public record ReviewStats(long taskCount,
                          long successCount,
                          long issueCount,
                          long totalTokens,
                          BigDecimal totalCost,
                          Long avgElapsedMillis) {
}
