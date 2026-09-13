package com.zhouziheng.review.diff;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public record FileDiff(String path,
                       String status,
                       int additions,
                       int deletions,
                       List<DiffHunk> hunks) {

    /**
     * 新文件中出现过的行号（含上下文行），用于校验模型给出的行号是否真实存在。
     */
    public Set<Integer> newLineNumbers() {
        return hunks.stream()
                .flatMap(hunk -> hunk.lines().stream())
                .map(DiffLine::newLineNo)
                .filter(no -> no > 0)
                .collect(Collectors.toSet());
    }
}
