package com.zhouziheng.review;

/**
 * 一次评审的可调参数。
 * <p>
 * 分成两组，对应两种完全不同的上下文策略：
 * <ul>
 *   <li>{@link #STRATEGY_PRELOAD}（预塞式）—— 我们先用符号索引算出 diff 引用了哪些类，
 *       把它们的骨架一次塞进提示词。maxFiles / totalBudget 控制"塞多少"。</li>
 *   <li>{@link #STRATEGY_AGENT}（agent 式）—— 什么都不塞，给模型两个工具让它自己边读边判断。
 *       maxRounds 控制"最多让它来回几轮"。</li>
 * </ul>
 * 两条链路拿到的 diff 完全一样，只有上下文来源不同，这样才能公平地比出差异。
 * <p>
 * 做成参数而不是常量，是因为它们本来就是这套系统里最主要的几个旋钮：
 * 调大看得更多但更贵，调小省钱但可能漏掉关键上下文。
 * 参数化之后，同一个 commit 可以反复跑不同配置做对比。
 */
public record ReviewOptions(String strategy, Integer maxFiles, Integer totalBudget, Integer maxRounds) {

    public static final String STRATEGY_PRELOAD = "preload";
    public static final String STRATEGY_AGENT = "agent";

    public static final int DEFAULT_MAX_FILES = 12;
    public static final int DEFAULT_TOTAL_BUDGET = 15_000;
    public static final int DEFAULT_MAX_ROUNDS = 8;

    public static final ReviewOptions DEFAULT = new ReviewOptions(null, null, null, null);

    public static ReviewOptions of(Integer maxFiles, Integer totalBudget) {
        return new ReviewOptions(STRATEGY_PRELOAD, maxFiles, totalBudget, null);
    }

    public static ReviewOptions agent() {
        return new ReviewOptions(STRATEGY_AGENT, null, null, null);
    }

    /** 未知取值一律退回默认策略，宁可多跑一次预塞式，也不让请求因为拼错一个字符串就失败 */
    public String strategyOrDefault() {
        return STRATEGY_AGENT.equals(strategy) ? STRATEGY_AGENT : STRATEGY_PRELOAD;
    }

    public boolean isAgent() {
        return STRATEGY_AGENT.equals(strategyOrDefault());
    }

    public int maxFilesOrDefault() {
        return maxFiles == null || maxFiles <= 0 ? DEFAULT_MAX_FILES : maxFiles;
    }

    public int totalBudgetOrDefault() {
        return totalBudget == null || totalBudget <= 0 ? DEFAULT_TOTAL_BUDGET : totalBudget;
    }

    public int maxRoundsOrDefault() {
        return maxRounds == null || maxRounds <= 0 ? DEFAULT_MAX_ROUNDS : maxRounds;
    }

    /**
     * 缓存签名。参数不同就是不同的评审结果，缓存必须区分开 ——
     * 否则调完参数点提交会直接命中上一轮的旧结果，"动态看效果"就成了看旧效果。
     * <p>
     * 策略也必须进签名：换成 agent 式之后如果命中了预塞式的旧结果，
     * 页面上看到的执行轨迹会是空的，对比也就无从谈起。
     */
    public String signature() {
        if (isAgent()) {
            return "agent-r" + maxRoundsOrDefault();
        }
        return "f" + maxFilesOrDefault() + "b" + totalBudgetOrDefault();
    }
}
