package com.zhouziheng.review.context;

/**
 * 一段被召回的相关代码。
 *
 * @param path     文件路径（仓库内相对路径）
 * @param skeleton 骨架内容：保留类声明、字段、方法签名，方法体已省略
 * @param rawChars 文件原始字符数，用来算骨架的压缩率
 */
public record CodeContext(String path, String skeleton, int rawChars) {
}
