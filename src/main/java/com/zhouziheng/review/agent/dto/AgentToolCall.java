package com.zhouziheng.review.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 模型发起的一次工具调用请求。
 * <p>
 * 注意它只是"请求"：模型输出了这段 JSON，真正执行的是我们本地的代码。
 * arguments 是模型拼出来的 JSON 字符串（不是对象），所以它可能语法错误、可能少传参数，
 * 执行前必须按不可信输入处理。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentToolCall(String id, String type, Function function) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Function(String name, String arguments) {
    }
}
