package com.zhouziheng.review;

import com.zhouziheng.review.model.TokenUsage;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 把 token 用量换算成费用。
 * <p>
 * 一次评审要跑很多轮，每一轮的 token 都要累加进来；
 * 计费口径必须统一，各算各的迟早会算飞。
 */
@Component
public class TokenPricing {

    private final ReviewPricingProperties properties;

    public TokenPricing(ReviewPricingProperties properties) {
        this.properties = properties;
    }

    /** 缓存命中的输入 token 单价只有未命中的几十分之一，必须从 promptTokens 里拆出来单独计价 */
    public TokenUsage calculate(int promptTokens, int completionTokens, int totalTokens, long cachedTokens) {
        BigDecimal cost = priceOf(promptTokens - cachedTokens, properties.inputPricePerMillion())
                .add(priceOf(cachedTokens, properties.cachedInputPricePerMillion()))
                .add(priceOf(completionTokens, properties.outputPricePerMillion()))
                .setScale(4, RoundingMode.HALF_UP);
        return new TokenUsage(promptTokens, completionTokens, totalTokens, cachedTokens, cost);
    }

    private BigDecimal priceOf(long tokens, BigDecimal pricePerMillion) {
        return pricePerMillion.multiply(BigDecimal.valueOf(tokens))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
    }
}
