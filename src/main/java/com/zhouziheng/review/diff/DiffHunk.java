package com.zhouziheng.review.diff;

import java.util.List;

public record DiffHunk(int oldStart, int newStart, List<DiffLine> lines) {
}
