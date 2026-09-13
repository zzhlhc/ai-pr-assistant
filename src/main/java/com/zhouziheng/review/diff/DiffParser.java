package com.zhouziheng.review.diff;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把统一 diff 文本解析成带行号的结构，供两处使用：
 * 1. 组装 Prompt 时把行号标给模型，降低它定位出错的概率
 * 2. 模型返回后回溯校验 file + line 是否真实存在，过滤幻觉
 */
public final class DiffParser {

    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*$");

    private DiffParser() {
    }

    public static FileDiff parse(String path, String status, Integer additions, Integer deletions, String patch) {
        List<DiffHunk> hunks = new ArrayList<>();
        List<DiffLine> lines = new ArrayList<>();

        int oldStart = 0;
        int newStart = 0;
        int newLineNo = 0;

        for (String raw : patch.split("\n", -1)) {
            Matcher matcher = HUNK_HEADER.matcher(raw);
            if (matcher.matches()) {
                if (!lines.isEmpty()) {
                    hunks.add(new DiffHunk(oldStart, newStart, lines));
                    lines = new ArrayList<>();
                }
                oldStart = Integer.parseInt(matcher.group(1));
                newStart = Integer.parseInt(matcher.group(2));
                newLineNo = newStart;
                continue;
            }

            char type = raw.isEmpty() ? ' ' : raw.charAt(0);
            if (type != '+' && type != '-' && type != ' ') {
                // 形如 "\ No newline at end of file" 的元信息行，跳过
                continue;
            }

            String content = raw.isEmpty() ? "" : raw.substring(1);
            if (type == '-') {
                lines.add(new DiffLine(-1, '-', content));
            } else {
                lines.add(new DiffLine(newLineNo++, type, content));
            }
        }

        if (!lines.isEmpty()) {
            hunks.add(new DiffHunk(oldStart, newStart, lines));
        }

        return new FileDiff(path, status, additions == null ? 0 : additions,
                deletions == null ? 0 : deletions, hunks);
    }
}
