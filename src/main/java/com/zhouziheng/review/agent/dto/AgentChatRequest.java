package com.zhouziheng.review.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 一轮请求的报文。字段名靠 SNAKE_CASE 命名策略转成 snake_case，
 * 所以 Java 侧用驼峰、协议侧是下划线，两边都不别扭。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentChatRequest(String model,
                               List<AgentMessage> messages,
                               List<AgentToolSpec> tools,
                               Double temperature) {
}
