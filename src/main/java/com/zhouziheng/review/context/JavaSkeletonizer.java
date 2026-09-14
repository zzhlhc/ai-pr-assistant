package com.zhouziheng.review.context;

import org.springframework.stereotype.Component;

/**
 * 把 Java 源文件压成"骨架"：保留类声明、字段声明、方法签名，丢掉方法体。
 * <p>
 * 为什么需要它：一个实体类动辄上千行，而评审真正需要看的信息只有两类 ——
 * <b>这个字段是什么类型、会不会是 null</b>，以及 <b>这个方法的签名长什么样</b>。
 * 方法体怎么写，跟"调用方会不会 NPE"没关系。
 * <p>
 * 实现刻意不引入 JavaParser 这类完整语法树库：
 * 那种依赖只为了一次文本压缩不值得。这里用一个更朴素但够用的规则 ——
 * 在类体这一层（大括号深度 1）保留所有行，一旦某个方法签名把深度推进到 2，
 * 就一直丢弃到深度回到 1 为止。
 */
@Component
public class JavaSkeletonizer {

    /** 骨架最多保留的行数。召回必须有预算，"全都要"最后会变成"什么都塞不进去" */
    private static final int MAX_LINES = 120;

    /** 类体所在的大括号深度 */
    private static final int CLASS_BODY_DEPTH = 1;

    public String skeletonize(String source) {
        if (source == null || source.isBlank()) {
            return "";
        }

        StringBuilder out = new StringBuilder();
        int depth = 0;
        int keptLines = 0;
        boolean insideMethodBody = false;
        boolean lastLineBlank = false;

        for (String raw : source.split("\n", -1)) {
            String trimmed = raw.strip();
            int before = depth;
            int net = netBraces(raw);
            depth += net;

            if (insideMethodBody) {
                if (depth <= CLASS_BODY_DEPTH) {
                    insideMethodBody = false;
                } else {
                    continue;
                }
            }

            // 深度超过类体的部分（内部类内部的字段、注解内的参数）统统不保留，
            // 这会让骨架丢掉内部类 —— 可接受的取舍，换来的是实现只有几十行
            if (before > CLASS_BODY_DEPTH) {
                continue;
            }

            if (trimmed.isEmpty()) {
                if (lastLineBlank || out.length() == 0) {
                    continue;
                }
                lastLineBlank = true;
            } else {
                lastLineBlank = false;
            }

            out.append(raw).append('\n');
            keptLines++;
            if (keptLines >= MAX_LINES) {
                out.append("// ...（骨架已截断，完整文件请查仓库）\n");
                break;
            }

            if (before == CLASS_BODY_DEPTH && net > 0 && looksLikeMethodSignature(trimmed)) {
                insideMethodBody = true;
            }
        }

        return out.toString();
    }

    private boolean looksLikeMethodSignature(String line) {
        return line.indexOf('(') > 0
                && !line.endsWith(";")
                && !line.startsWith("//")
                && !line.startsWith("*");
    }

    private int netBraces(String line) {
        int net = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '{') {
                net++;
            } else if (c == '}') {
                net--;
            }
        }
        return net;
    }
}
