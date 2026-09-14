package com.zhouziheng.gitee.dto;

/**
 * 文件树节点。
 *
 * @param path 完整相对路径（目录节点也是完整路径，不是文件名）
 * @param type tree=目录，blob=文件
 * @param sha  该节点的 sha
 * @param size 文件字节数
 */
public record GiteeTreeNode(String path, String type, String sha, Long size) {
}
