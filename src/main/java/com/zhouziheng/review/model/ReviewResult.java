package com.zhouziheng.review.model;

import com.zhouziheng.review.agent.AgentTrace;

/**
 * 一次评审的完整产出：报告 + 这次花了多少 token、多少钱、多久 + 模型自己的执行轨迹。
 * <p>
 * 只返回 ReviewReport 的话成本数据和上下文来源就丢了，
 * 而后者恰恰是回答"这次评审凭什么得出这个结论"的依据：
 * trace 里记着它读过哪些文件、每一轮为什么去读。
 */
public record ReviewResult(ReviewReport report,
                           TokenUsage usage,
                           long elapsedMillis,
                           AgentTrace trace) {
}
