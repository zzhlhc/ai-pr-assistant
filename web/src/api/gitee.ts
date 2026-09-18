import { http } from './http'
import type { GiteeCommitSummary, RepoOption } from '../types/gitee'

/**
 * 预置的仓库清单，一次全量返回（200 条）。
 * 过滤放在浏览器里做：输入即时出结果，也不用每敲一个字就打一次接口。
 */
export async function listRepos(): Promise<RepoOption[]> {
  const { data } = await http.get<RepoOption[]>('/gitee/repos')
  return data
}

/**
 * 某个仓库最近的 30 个 commit。
 * repo 是 owner/repo，斜杠原样带进路径即可，后端按两个路径段接。
 */
export async function listCommits(repo: string): Promise<GiteeCommitSummary[]> {
  const { data } = await http.get<GiteeCommitSummary[]>(`/gitee/repos/${repo}/commits`)
  return data
}
