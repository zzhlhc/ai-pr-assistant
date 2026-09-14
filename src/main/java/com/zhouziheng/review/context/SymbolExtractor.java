package com.zhouziheng.review.context;

import com.zhouziheng.review.diff.DiffHunk;
import com.zhouziheng.review.diff.DiffLine;
import com.zhouziheng.review.diff.FileDiff;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
        Map<String, Integer> fileFrequency = new HashMap<>();
        Set<String> changedTypes = new HashSet<>();

        for (FileDiff file : diffs) {
            String ownType = simpleNameOf(file.path());
            if (ownType != null) {
                changedTypes.add(ownType);
            }

            Set<String> inThisFile = new HashSet<>();
            for (DiffHunk hunk : file.hunks()) {
                for (DiffLine line : hunk.lines()) {
                    // 删除行在新文件里已经不存在，模型要评论也应该基于现存代码
                    if (line.type() == '-') {
                        continue;
                    }
                    scan(line.content(), imports, counter, inThisFile);
                }
            }
            inThisFile.forEach(name -> fileFrequency.merge(name, 1, Integer::sum));
        }

        // 本次改动自己的类不用召回：完整内容本来就在 diff 里，再把定义塞一遍纯属浪费 token
        changedTypes.forEach(counter::remove);

        // 权重用 TF-IDF，不是单纯的词频。
        // 只按词频排的话，出现次数是"跨文件累加"出来的：一个符号在越多的文件里出现，累加值就越高。
        // 于是 String、List、Override 这种每个文件都在用的类型天然排在最前面，
        // 而真正体现这次改动主题的类（只在少数文件里被反复提到）反而排在后面。
        // IDF 就是修这个的：df 越接近文件总数，说明它越像语法层面的通用词，权重越低。
        // 注意这里只算权重、不排序：排序要等索引过滤之后再做。
        int docCount = Math.max(diffs.size(), 1);
        List<ReferencedSymbols.Symbol> weighted = counter.entrySet().stream()
                .map(e -> new ReferencedSymbols.Symbol(e.getKey(),
                        weight(e.getValue(), fileFrequency.getOrDefault(e.getKey(), 1), docCount)))
                .toList();

        return new ReferencedSymbols(new ArrayList<>(imports), weighted);
    }

    /**
     * TF-IDF 权重。IDF 用平滑形式 {@code log((N+1)/(df+1)) + 1}：
     * <ul>
     *   <li>df 等于文件总数时（每个文件都出现）IDF 取到最小值 1，权重退回纯词频 ——
     *       这类符号本来就该被索引挡掉，不需要在排序上特殊处理</li>
     *   <li>只有一个文件的 diff 时 N=1、df=1，IDF 恒为 1，整体退化成纯词频，不会因为样本不足算出怪异结果</li>
     * </ul>
     */
    private double weight(int tf, int df, int docCount) {
        return tf * (Math.log((docCount + 1.0) / (df + 1.0)) + 1.0);
    }

    private void scan(String text, Set<String> imports, Map<String, Integer> counter, Set<String> inThisFile) {
        Matcher importMatcher = IMPORT.matcher(text);
        if (importMatcher.find()) {
            imports.add(importMatcher.group(1));
            return;
        }

        Matcher typeMatcher = TYPE_NAME.matcher(text);
        while (typeMatcher.find()) {
            String name = typeMatcher.group(1);
            counter.merge(name, 1, Integer::sum);
            inThisFile.add(name);
        }
    }

    private String simpleNameOf(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.endsWith(".java") ? name.substring(0, name.length() - ".java".length()) : null;
    }
}
