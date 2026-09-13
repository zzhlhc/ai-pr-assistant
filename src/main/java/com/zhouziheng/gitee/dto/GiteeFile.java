package com.zhouziheng.gitee.dto;

/**
 * Gitee commit 详情里的单个文件变更。
 *
 * @param filename  文件路径
 * @param status    added / modified / removed / renamed
 * @param additions 新增行数
 * @param deletions 删除行数
 * @param patch     统一 diff 文本，二进制或超大文件时为空
 * @param truncated 该 patch 是否被 Gitee 截断
 */
public record GiteeFile(String filename,
                        String status,
                        Integer additions,
                        Integer deletions,
                        String patch,
                        Boolean truncated) {
}
