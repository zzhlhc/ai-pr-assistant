package com.zhouziheng.gitee.dto;

import java.util.List;

/**
 * Gitee 的递归文件树。/repos/{owner}/{repo}/git/trees/{sha}?recursive=1
 * <p>
 * 一次调用就能拿到整个仓库的路径清单（本项目实测 7451 个条目），
 * 这是"类名 → 文件路径"索引的全部原料，成本只有一次 HTTP 请求。
 */
public record GiteeTree(String sha, List<GiteeTreeNode> tree, Boolean truncated) {
}
