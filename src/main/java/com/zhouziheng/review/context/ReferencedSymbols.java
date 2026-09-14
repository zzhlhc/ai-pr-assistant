package com.zhouziheng.review.context;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 从一次 diff 里提取出的、值得回头去看定义的符号。
 *
 * @param imports     形如 coopwire.common.base.module.file.FileInfo 的全限定名，
 *                    来自 import 语句，最精确，优先级最高
 * @param identifiers 代码里出现的类名（如 ApiParameterGroup），按出现次数降序，
 *                    可能包含 JDK 类型等噪声，靠索引能否命中来过滤
 */
public record ReferencedSymbols(List<String> imports, List<String> identifiers) {

    public static ReferencedSymbols empty() {
        return new ReferencedSymbols(List.of(), List.of());
    }

    public boolean isEmpty() {
        return imports.isEmpty() && identifiers.isEmpty();
    }

    /** 追加一批全限定名（如从被改动文件 import 清单里补出来的），去重后返回新对象 */
    public ReferencedSymbols withImports(List<String> more) {
        if (more.isEmpty()) {
            return this;
        }
        Set<String> merged = new LinkedHashSet<>(imports);
        merged.addAll(more);
        return new ReferencedSymbols(List.copyOf(merged), identifiers);
    }
}
