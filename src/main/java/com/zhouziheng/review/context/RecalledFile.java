package com.zhouziheng.review.context;

/**
 * 召回结果的轻量视图，用于持久化和接口返回。
 * <p>
 * 刻意不带骨架正文：骨架动辄上万字符，存下来既占空间又撑大接口响应，
 * 而页面真正需要展示的是"召回了哪些文件、压到多少字符"这个对比关系。
 *
 * @param path     文件路径
 * @param chars    骨架字符数（实际进入提示词的部分）
 * @param rawChars 文件原始字符数
 */
public record RecalledFile(String path, int chars, int rawChars) {

    public static RecalledFile of(CodeContext context) {
        return new RecalledFile(context.path(), context.skeleton().length(), context.rawChars());
    }

    /** 压缩率，例如 26 表示压到了原来的 26% */
    public int percent() {
        return rawChars == 0 ? 0 : chars * 100 / rawChars;
    }
}
