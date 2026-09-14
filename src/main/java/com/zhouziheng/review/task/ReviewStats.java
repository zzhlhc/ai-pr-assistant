package com.zhouziheng.review.task;

import java.math.BigDecimal;

/**
 * 成本账：单次评审用了多少 token、花了多少钱，累计下来是多少。
 * <p>
 * 前 6 个字段是一次 SQL 聚合出来的；cacheHits 不一样 ——
 * 命中缓存不会产生新任务行（直接复用历史结果），库里根本没有痕迹，
 * 所以它只能是进程内的计数器。这也是它跟其余字段分开的原因。
 */
public record ReviewStats(long taskCount,
                          long successCount,
                          long issueCount,
                          long totalTokens,
                          BigDecimal totalCost,
                          Long avgElapsedMillis,
                          long cacheHits) {

    public ReviewStats withCacheHits(long hits) {
        return new ReviewStats(taskCount, successCount, issueCount, totalTokens, totalCost,
                avgElapsedMillis, hits);
    }
}
