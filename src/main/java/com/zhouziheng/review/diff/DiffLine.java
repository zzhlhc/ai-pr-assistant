package com.zhouziheng.review.diff;

/**
 * diff 中的一行。
 *
 * @param newLineNo 该行在**新文件**中的行号；删除行在新文件中不存在，固定为 -1
 * @param type      '+' 新增、'-' 删除、' ' 上下文
 * @param content   行内容（不含 diff 前缀）
 */
public record DiffLine(int newLineNo, char type, String content) {
}
