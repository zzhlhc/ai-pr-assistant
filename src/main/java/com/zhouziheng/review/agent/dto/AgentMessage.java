package com.zhouziheng.review.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 一条对话消息，发送和接收共用。
 * <p>
 * 不复用是没必要的 —— 模型返回的 assistant 消息要原样回填进下一轮请求（含 tool_calls），
 * 字段完全对得上，拆成两个类反而要手工搬运一遍。
 * <p>
 * {@code reasoningContent} 是 DeepSeek 特有的字段，回填时漏掉它接口会直接 400：
 * "The reasoning_content in the thinking mode must be passed back to the API"。
 * OpenAI 自己的协议里没有这个字段，只有真接过 DeepSeek 才会踩到。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentMessage(String role,
                           String content,
                           String reasoningContent,
                           List<AgentToolCall> toolCalls,
                           String toolCallId) {

    public static AgentMessage system(String content) {
        return new AgentMessage("system", content, null, null, null);
    }

    public static AgentMessage user(String content) {
        return new AgentMessage("user", content, null, null, null);
    }

    /** 工具执行结果。toolCallId 必须和发起那次调用的 id 对上，对不上模型会认为工具没返回。 */
    public static AgentMessage tool(String toolCallId, String content) {
        return new AgentMessage("tool", content, null, null, toolCallId);
    }
}
