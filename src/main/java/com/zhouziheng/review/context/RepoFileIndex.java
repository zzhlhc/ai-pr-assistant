package com.zhouziheng.review.context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 仓库的符号索引：给一个类名，告诉你它在哪个文件。
 * <p>
 * 这是"精准召回"的第一半。有了它，从 diff 里看到 {@code ApiParameterGroup}
 * 就能立刻定位到文件，而不需要把整个仓库塞进向量库做语义检索 ——
 * 代码场景里符号引用是精确的，用不上模糊匹配。
 *
 * @param byPackagePath 包相对路径 → 完整路径，例如 coopwire/a/b/C.java → 模块名/src/main/java/coopwire/a/b/C.java
 * @param bySimpleName  简单类名 → 完整路径，同名类保留路径最短的那个
 * @param paths         全部 java 文件路径，按原样保留、不去重也不筛包结构。
 *                      存在的理由：上面两个 Map 都是"给精确输入换精确输出"，一旦输入的类名是猜的、
 *                      或者只知道一个 URL 路径段 / 模块名，它们就完全用不上。
 *                      这份清单是给模糊查找用的 —— 它是 {@link #search} 的数据来源，
 *                      单独存一份而不是复用 byPackagePath.values()，是因为后者用 putIfAbsent 建，
 *                      同包同名的类（多模块仓库里很常见）会被吃掉一个。
 */
public record RepoFileIndex(Map<String, String> byPackagePath, Map<String, String> bySimpleName,
                            List<String> paths) {

    public static RepoFileIndex empty() {
        return new RepoFileIndex(Map.of(), Map.of(), List.of());
    }

    /**
     * 按关键词在文件路径里做模糊查找，忽略大小写、按出现顺序匹配。
     * <p>
     * 传入的关键词不含 {@code *} 时就是一次普通的子串匹配；含 {@code *} 时按 {@code *} 拆成若干段，
     * 要求它们在路径里依次出现（相当于一个极简的 glob）。之所以顺手兼容通配符，是因为模型
     * 看到"列文件"很容易写成 {@code *Controller.java} —— 直接按子串匹配会一个都命不中，
     * 白白浪费一轮，而按段匹配天然就是后缀匹配。
     * <p>
     * 结果按路径长度升序：路径短通常意味着它在更靠近根目录的公共模块里，是更可能被引用的那个，
     * 也是模型应该先看的那个。
     */
    public List<String> search(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String[] parts = keyword.toLowerCase().split("\\*+");
        List<String> hits = new ArrayList<>();
        for (String path : paths) {
            if (matches(path.toLowerCase(), parts)) {
                hits.add(path);
            }
        }
        hits.sort(Comparator.comparingInt(String::length));
        return hits;
    }

    /** 各段必须按顺序出现在路径里；{@code *} 拆出来的空段直接跳过 */
    private static boolean matches(String path, String[] parts) {
        int from = 0;
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            int at = path.indexOf(part, from);
            if (at < 0) {
                return false;
            }
            from = at + part.length();
        }
        return from > 0;
    }

    /**
     * 用 import 的全限定名精确定位：Java 的包名就是目录名，所以
     * {@code coopwire.provider.quality.dto.ApiParameterGroup}
     * 必然对应某个 {@code .../coopwire/provider/quality/dto/ApiParameterGroup.java}。
     */
    public String resolveFqn(String fqn) {
        return byPackagePath.get(fqn.replace('.', '/') + ".java");
    }

    /**
     * 用代码里出现的类名定位。同名类会命中路径最短的那个 ——
     * 路径短通常意味着它在更靠近根目录的公共模块里，是更可能被引用的那个。
     */
    public String resolve(String simpleName) {
        return bySimpleName.get(simpleName);
    }

    public int size() {
        return bySimpleName.size();
    }
}
