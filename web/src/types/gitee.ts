/** 跟后端 com.zhouziheng.review.catalog.RepoOption 一一对应 */

export interface RepoOption {
  /** 自己账号下的真实路径，如 dumbbell5kg/RuoYi，直接当评审接口的 repo 用 */
  fullName: string
  /** 下拉框里显示的名字，如 若依/RuoYi（Gitee 的 human_name，不是 URL 里的 y_project） */
  displayName: string
  description: string | null
  stars: number | null
  /** 原仓库路径，如 y_project/RuoYi；不是 fork 的为 null，用来让搜索也能命中它 */
  sourceFullName: string | null
}

export interface GiteeCommitSummary {
  sha: string
  commit: {
    message: string
    /** date 是 ISO 8601 带时区的字符串 */
    author: { name: string | null; email: string | null; date: string | null } | null
  } | null
}
