package com.zhouziheng.review.agent.dto;

import java.util.Map;

/**
 * 告诉模型"有哪些工具可用"的声明，每轮请求都要带上。
 * <p>
 * 模型只会在这个清单里挑工具，清单里没有的能力它再想要也做不到。
 * description 是这里最值钱的部分：模型选不选得对工具、参数填得对不对，基本靠它。
 */
public record AgentToolSpec(String type, Definition function) {

    public record Definition(String name, String description, Map<String, Object> parameters) {
    }

    public static AgentToolSpec of(String name, String description, Map<String, Object> parameters) {
        return new AgentToolSpec("function", new Definition(name, description, parameters));
    }
}
