package com.zhouziheng.review.context;

import java.util.List;

/**
 * 一次召回的完整产出，含过程指标。
 * <p>
 * 预览功能要展示的不只是"召回了什么"，还有"从多少候选里挑出来、花了多久" ——
 * 调参的时候，"候选 33 个只召回 7 个"和"候选 8 个召回 7 个"完全是两个结论。
 */
public record RecallResult(int candidateFiles, List<CodeContext> contexts, long elapsedMillis) {
}
