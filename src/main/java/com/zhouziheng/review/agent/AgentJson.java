package com.zhouziheng.review.agent;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * agent 链路专用的一条 JSON 规则。
 * <p>
 * Java 侧用驼峰、协议侧用下划线，靠命名策略转换，两边各自保持自己的习惯；
 * 忽略未知属性则是因为接口返回的字段比我们关心的多（模型版本、指纹、各类明细计数），
 * 多一个字段就解析失败，会让整条链路变得很脆。
 */
final class AgentJson {

    static final JsonMapper MAPPER = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private AgentJson() {
    }
}
