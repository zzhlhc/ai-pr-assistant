package com.zhouziheng.review.agent;

import java.util.List;

/**
 * 一次 agent 式评审的完整执行轨迹。
 * <p>
 * 上下文是模型自己一轮轮找出来的，每一步都有据可查 ——
 * 它看了什么、为什么看、看完之后又决定去看什么。
 */
public record AgentTrace(int rounds, List<AgentStep> steps) {

    public static AgentTrace empty() {
        return new AgentTrace(0, List.of());
    }

    public int toolCallCount() {
        return (int) steps.stream().filter(step -> !step.isFinal()).count();
    }

    /** 模型一共读过哪些文件，按首次读取顺序去重。这就是 agent 自己"用出来"的上下文清单。 */
    public List<String> readFiles() {
        return steps.stream()
                .filter(step -> "read_file".equals(step.toolName()))
                .map(AgentStep::target)
                .distinct()
                .toList();
    }
}
