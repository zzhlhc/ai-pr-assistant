package com.zhouziheng.review.model;

import java.math.BigDecimal;

/**
 * 单次模型调用的 token 用量与费用。
 * <p>
 * 缓存命中的输入 token 单价更低，所以要单独留一个字段，
 * 只记 promptTokens 的话成本是算不准的。
 */
public record TokenUsage(int promptTokens,
                         int completionTokens,
                         int totalTokens,
                         long cachedTokens,
                         BigDecimal cost) {
}
