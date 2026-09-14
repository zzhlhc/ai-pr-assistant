package com.zhouziheng.review.context;

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
 */
public record RepoFileIndex(Map<String, String> byPackagePath, Map<String, String> bySimpleName) {

    public static RepoFileIndex empty() {
        return new RepoFileIndex(Map.of(), Map.of());
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
