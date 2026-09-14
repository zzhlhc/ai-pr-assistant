package com.zhouziheng.gitee.dto;

/**
 * Gitee contents 接口返回的文件内容。
 *
 * @param content 文件正文，base64 编码
 * @param encoding 实测为 base64
 */
public record GiteeContent(String name, String path, Long size, String encoding, String content, String type) {
}
