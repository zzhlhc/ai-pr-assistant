package com.zhouziheng.review;

import com.zhouziheng.review.agent.AgentStep;

/**
 * 评审过程中的进度回调，两档粒度：
 * <ul>
 *   <li>{@link #stage} —— "现在在做什么"的一句话。它是个状态，后一句会顶掉前一句，两条链路都会调。</li>
 *   <li>{@link #step} —— agent 式每跑完一步（调完一次工具、或得出终答）就回调一次，
 *       是累积的履历，不会互相覆盖。</li>
 * </ul>
 * 只有 stage 一个抽象方法，所以 {@code stage -> ...} 这种写法照样能当 lambda 直接传。
 */
@FunctionalInterface
public interface ReviewProgress {

    void stage(String text);

    /** 预塞式没有这一步，默认什么都不做 */
    default void step(AgentStep step) {
    }

    /** 不关心进度时用这个 */
    static ReviewProgress none() {
        return text -> {
        };
    }
}
