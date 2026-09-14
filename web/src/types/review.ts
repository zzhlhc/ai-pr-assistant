/** 跟后端 com.zhouziheng.review.model 下的 record 一一对应 */

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

export interface ReviewRequest {
  repo: string
  commitSha: string
}
