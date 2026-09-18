package com.zhouziheng.gitee.dto;

/**
 * /user/repos 返回的一个仓库，只留下拉框用得上的字段。
 *
 * @param fullName       形如 dumbbell5kg/RuoYi，提交评审时用的就是它
 * @param displayName    Gitee 页面上显示的名字，形如 若依/RuoYi。
 *                       和 full_name 不是一回事：full_name 是 URL 里的路径（y_project/RuoYi），
 *                       human_name 才是页面上那个名字（若依/RuoYi）。fork 的话取原仓库的
 * @param stars          原仓库的 star 数（fork 的话取 parent 的），让下拉框看起来像那么回事
 * @param sourceFullName 原仓库路径，如 y_project/RuoYi；不是 fork 的为 null，用来让搜索也能命中原路径
 */
public record GiteeRepoSummary(String fullName, String displayName, String description, Integer stars,
                               String sourceFullName) {
}
