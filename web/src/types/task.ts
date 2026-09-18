/** 跟后端 com.zhouziheng.review.task 和 com.zhouziheng.review.model 下的 record 一一对应 */

export type TaskStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED'

export type Severity = 'CRITICAL' | 'MAJOR' | 'MINOR' | 'INFO'

export interface ReviewIssue {
  severity: Severity
  category: string
  file: string
  line: number
  issue: string
  suggestion: string
  evidence: string
}

export interface ReviewReport {
  summary: string
  issues: ReviewIssue[]
}

/**
 * agent 的一轮轨迹，对应后端 AgentStep。
 * toolName 为空表示这一轮模型没调工具、直接给出了结论。
 */
export interface AgentStep {
  round: number
  /** 模型这一轮说的话，是它"为什么去读那个文件"的唯一线索 */
  thought: string | null
  toolName: string | null
  /** 操作对象：查符号时是符号名，读文件时是文件路径 */
  target: string | null
  /** 模型给工具填的参数原文 */
  arguments: string | null
  /** 工具返回值摘要 */
  resultSummary: string | null
  promptTokens: number
  completionTokens: number
  cachedTokens: number
  elapsedMillis: number
}

export interface TokenUsage {
  promptTokens: number
  completionTokens: number
  totalTokens: number
  cachedTokens: number
  cost: number
}

/** 任务完整快照，SSE 推送和详情接口返回的都是它 */
export interface ReviewTask {
  id: string
  /** 自己账号下的真实路径，接口调用和落库都用它 */
  repo: string
  /** 仓库显示名，如 若依/RuoYi；老数据为 null，显示时退回 repo */
  repoName: string | null
  commitSha: string
  /** 这条结果是用哪版提示词跑出来的 */
  promptVersion: string
  status: TaskStatus
  stage: string | null
  report: ReviewReport | null
  /** 模型自己读代码的全过程：读了什么、每一轮为什么读 */
  steps: AgentStep[]
  error: string | null
  usage: TokenUsage | null
  elapsedMillis: number | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
}

/** 列表页视图，后端一条 SQL 查出来，不带问题明细 */
export interface TaskSummary {
  id: string
  repo: string
  /** 仓库显示名，如 若依/RuoYi；老数据为 null，显示时退回 repo */
  repoName: string | null
  commitSha: string
  status: TaskStatus
  stage: string | null
  summary: string | null
  issueCount: number
  cost: number | null
  elapsedMillis: number | null
  createdAt: string
  finishedAt: string | null
}

/** 成本账，后端 /api/stats 返回 */
export interface ReviewStats {
  taskCount: number
  successCount: number
  issueCount: number
  totalTokens: number
  totalCost: number
  avgElapsedMillis: number | null
}
