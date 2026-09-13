package com.zhouziheng.review.model;

import java.util.List;

public record ReviewReport(String summary, List<ReviewIssue> issues) {
}
