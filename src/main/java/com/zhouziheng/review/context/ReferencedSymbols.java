package com.zhouziheng.review.context;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 从一次 diff 里提取出的、值得回头去看定义的符号。
 *
 * @param imports     形如 coopwire.common.base.module.file.FileInfo 的全限定名，
 *                    来自 import 语句，最精确，优先级最高
 * @param identifiers 代码里出现的类名（如 ApiParameterGroup）和它们的 TF-IDF 权重。
 *                    <b>故意不在这里排序</b>：权重只有在"仓库里真实存在的类"之间比较才有意义，
 *                    排序交给 {@link CodeContextRecaller}，让它先用索引把
 *                    String、List、Override 这类仓库里没有的符号剔掉再排
 */
public record ReferencedSymbols(List<String> imports, List<Symbol> identifiers) {

    /** 一个候选符号和它的 TF-IDF 权重 */
    public record Symbol(String name, double weight) {
    }

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
