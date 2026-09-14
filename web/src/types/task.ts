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
  repo: string
  commitSha: string
  /** 提示词版本，也是缓存的失效开关：同一 commit + 同一版本才会复用历史结果 */
  promptVersion: string
  status: TaskStatus
  stage: string | null
  report: ReviewReport | null
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
  cacheHits: number
}
