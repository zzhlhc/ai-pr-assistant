package com.zhouziheng.gitee.dto;

/**
 * commits 列表里的一条记录。
 * <p>
 * 结构和详情接口一致，只是没有 files —— 列表接口本来就不带 diff，
 * 这正是它便宜的原因：先让用户挑，挑中了再按 sha 拉详情。
 */
public record GiteeCommitSummary(String sha, GiteeCommitInfo commit) {
}
