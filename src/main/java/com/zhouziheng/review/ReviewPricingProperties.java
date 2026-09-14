package com.zhouziheng.review;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * 模型计费单价（元 / 百万 token）。
 * <p>
 * 单价会变，不同账号、不同中转站的差别也很大，所以做成配置项而不是写死在代码里。
 * application.yml 里的默认值只是占位，真上线前按自己的账单改。
 * <p>
 * 输入 token 分两档：命中上下文缓存的便宜很多，所以缓存命中的部分要单独计价，
 * 不能直接用 promptTokens 乘输入单价。
 */
@ConfigurationProperties(prefix = "review.pricing")
public record ReviewPricingProperties(
        BigDecimal inputPricePerMillion,
        BigDecimal cachedInputPricePerMillion,
        BigDecimal outputPricePerMillion) {
}
