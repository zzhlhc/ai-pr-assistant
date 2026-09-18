package com.zhouziheng.gitee.dto;

/**
 * @param date ISO 8601 带时区的提交时间，只有 commit 里的 author 带这个字段
 */
public record GiteeAuthor(String name, String email, String date) {
}
