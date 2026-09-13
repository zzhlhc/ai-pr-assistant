package com.zhouziheng.review.model;

public record ReviewIssue(Severity severity,
                          String category,
                          String file,
                          int line,
                          String issue,
                          String suggestion,
                          String evidence) {
}
