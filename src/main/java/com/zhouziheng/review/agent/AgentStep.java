package com.zhouziheng.review.agent;

/**
 * 一轮模型调用的轨迹。
 * <p>
 * {@code toolName} 为空表示这一轮模型没有调工具、直接给出了最终报告 ——
 * 把它也记成一步，前端的时间线才是完整的，看得出"一共聊了几轮、最后在哪一轮收敛"。
 *
 * @param thought       模型这一轮说的话，是它"为什么去读那个文件"的唯一线索
 * @param target        这次调用作用的对象：查符号时是符号名，读文件时是文件路径
 * @param arguments     模型给工具填的参数原文，调错参数时这是第一现场
 * @param resultSummary 工具返回值的前若干字符，完整内容太长，保留开头足够判断读到了什么
 */
public record AgentStep(int round,
                        String thought,
                        String toolName,
                        String target,
                        String arguments,
                        String resultSummary,
                        int promptTokens,
                        int completionTokens,
                        int cachedTokens,
                        long elapsedMillis) {

    public boolean isFinal() {
        return toolName == null;
    }
}
