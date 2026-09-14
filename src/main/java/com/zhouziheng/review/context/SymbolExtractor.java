package com.zhouziheng.review.context;

import com.zhouziheng.review.diff.DiffHunk;
import com.zhouziheng.review.diff.DiffLine;
import com.zhouziheng.review.diff.FileDiff;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 diff 文本里"问"出模型需要但看不到的东西：这次改动引用了哪些类？
 * <p>
 * 两类信号，精确度完全不同：
 * <ol>
 *   <li>import 语句 —— 直接给出全限定名，100% 准确，这是代码特有的"免费标签"</li>
 *   <li>代码里出现的类名 —— 有噪声（String、List 都会被扫到），
 *       但噪声可以在查索引时自然被过滤掉：JDK 类型不在仓库的文件树里，查不到就是查不到</li>
 * </ol>
 * 注意这里的判断：<b>索引本身就是过滤器，不需要维护一张"JDK 类名黑名单"</b>。
 */
@Component
public class SymbolExtractor {

    private static final Pattern IMPORT =
            Pattern.compile("^\\s*import\\s+(?:static\\s+)?([A-Za-z_][\\w.]*)\\s*;");

    /** 首字母大写、长度 3 以上、不含下划线（所以 MAX_RETRY_COUNT 这类常量不会被误抓） */
    private static final Pattern TYPE_NAME = Pattern.compile("\\b([A-Z][A-Za-z0-9]{2,})\\b");

    public ReferencedSymbols extract(List<FileDiff> diffs) {
        Set<String> imports = new LinkedHashSet<>();
        Map<String, Integer> counter = new HashMap<>();
        Set<String> changedTypes = new HashSet<>();

        for (FileDiff file : diffs) {
            String ownType = simpleNameOf(file.path());
            if (ownType != null) {
                changedTypes.add(ownType);
            }

            for (DiffHunk hunk : file.hunks()) {
                for (DiffLine line : hunk.lines()) {
                    // 删除行在新文件里已经不存在，模型要评论也应该基于现存代码
                    if (line.type() == '-') {
                        continue;
                    }
                    scan(line.content(), imports, counter);
                }
            }
        }

        // 本次改动自己的类不用召回：完整内容本来就在 diff 里，再把定义塞一遍纯属浪费 token
        changedTypes.forEach(counter::remove);

        // 这里不截断：截断必须发生在"能不能在索引里查到"之后。
        // 一个 commit 实测扫出 107 个去重符号，Top30 里只有 14 个真的在仓库里，
        // 另外 16 个（String、List、Collectors……）是 JDK 和框架类型，白占名额。
        // 名额被它们占掉之后，FeedbackProcessingTeam 这种频次低、但确实存在的类排在 55 名，永远进不来。
        // 排序在这里做（频次是唯一能拿到的相关性信号），截断交给查索引的那一步。
        List<String> ranked = counter.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed())
                .map(Map.Entry::getKey)
                .toList();

        return new ReferencedSymbols(new ArrayList<>(imports), ranked);
    }

    private void scan(String text, Set<String> imports, Map<String, Integer> counter) {
        Matcher importMatcher = IMPORT.matcher(text);
        if (importMatcher.find()) {
            imports.add(importMatcher.group(1));
            return;
        }

        Matcher typeMatcher = TYPE_NAME.matcher(text);
        while (typeMatcher.find()) {
            counter.merge(typeMatcher.group(1), 1, Integer::sum);
        }
    }

    private String simpleNameOf(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.endsWith(".java") ? name.substring(0, name.length() - ".java".length()) : null;
    }
}
