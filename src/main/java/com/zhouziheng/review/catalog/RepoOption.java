package com.zhouziheng.review.catalog;

/**
 * 仓库下拉框里的一个选项。
 *
 * @param fullName       形如 dumbbell5kg/RuoYi，正好就是提交评审时要的 owner/repo
 * @param displayName    下拉框里显示的名字，如 若依/RuoYi（Gitee 的 human_name）。
 *                       演示时看的是原项目，所以不显示自己账号名
 * @param sourceFullName 原仓库路径，如 y_project/RuoYi；不是 fork 的为 null，
 *                       留着是为了搜索时输入原路径也能命中
 */
public record RepoOption(String fullName, String displayName, String description, Integer stars,
                         String sourceFullName) {
}
