package com.zhouziheng.review.model;

public enum Severity {
    /** 会导致线上故障或数据错误 */
    CRITICAL,
    /** 明确的缺陷或性能问题 */
    MAJOR,
    /** 可改进 */
    MINOR,
    /** 提示 */
    INFO
}
