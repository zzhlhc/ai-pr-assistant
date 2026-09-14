package com.zhouziheng.review;

/**
 * 一次评审的可调参数。
 * <p>
 * 这两个值原本是 {@code CodeContextRecaller} 里的常量。做成参数，是因为
 * 召回数量和字符预算是上下文召回最主要的两个旋钮：调大召回更多但更贵，
 * 调小省钱但可能漏掉关键上下文。参数化之后，同一份 diff 可以对比不同配置的效果。
 */
public record ReviewOptions(Integer maxFiles, Integer totalBudget) {

    public static final int DEFAULT_MAX_FILES = 12;
    public static final int DEFAULT_TOTAL_BUDGET = 15_000;

    public static final ReviewOptions DEFAULT = new ReviewOptions(null, null);

    public static ReviewOptions of(Integer maxFiles, Integer totalBudget) {
        return new ReviewOptions(maxFiles, totalBudget);
    }

    public int maxFilesOrDefault() {
        return maxFiles == null || maxFiles <= 0 ? DEFAULT_MAX_FILES : maxFiles;
    }

    public int totalBudgetOrDefault() {
        return totalBudget == null || totalBudget <= 0 ? DEFAULT_TOTAL_BUDGET : totalBudget;
    }

    /**
     * 缓存签名。参数不同就是不同的评审结果，缓存必须区分开 ——
     * 否则调完参数点提交会直接命中上一轮的旧结果，"动态看效果"就成了看旧效果。
     */
    public String signature() {
        return "f" + maxFilesOrDefault() + "b" + totalBudgetOrDefault();
    }
}
