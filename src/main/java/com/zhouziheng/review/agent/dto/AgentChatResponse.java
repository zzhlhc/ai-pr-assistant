package com.zhouziheng.review.agent.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 一轮响应的报文（只保留我们真正要用的字段，其余靠忽略未知属性丢掉）。
 * <p>
 * finishReason 是判断"这轮结束了没有"的唯一依据：
 * {@code tool_calls} 表示模型要调工具、还得继续循环；{@code stop} 才是它给出了最终答案。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentChatResponse(List<Choice> choices, Usage usage) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(AgentMessage message, String finishReason) {
    }

    /**
     * promptCacheHitTokens 是 DeepSeek 的缓存命中计数。
     * 多轮对话里前面的历史每轮都会重发，命中缓存后单价只有未命中的 1/50，
     * 这是 agent 式上下文能扛住多轮的关键。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(Integer promptTokens, Integer completionTokens, Integer totalTokens,
                        Integer promptCacheHitTokens) {
    }

    public AgentMessage message() {
        return choices == null || choices.isEmpty() ? null : choices.get(0).message();
    }

    /** 模型这轮是要调工具，还是给出了最终答案 */
    public boolean wantsToolCall() {
        AgentMessage message = message();
        return message != null && message.toolCalls() != null && !message.toolCalls().isEmpty();
    }

    public Usage usageOrEmpty() {
        return usage == null ? new Usage(0, 0, 0, 0) : usage;
    }
}
