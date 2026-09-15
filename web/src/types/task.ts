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
 * 上下文策略。两条链路的 diff 完全一样，只有"上下文从哪来"不同：
 * - preload：我们先用符号索引算出 diff 引用了哪些类，把它们的骨架塞进提示词；
 * - agent：什么都不塞，给模型 find_type / read_file 两个工具，让它自己边读边判断。
 */
export type ContextStrategy = 'preload' | 'agent'

/**
 * 评审参数，对应后端 ReviewOptions。不传就用后端的默认值。
 * 后端会把它拼成签名（如 f12b15000、agent-r8）当缓存 key 的一部分，
 * 所以改参数（包括换策略）都不会命中上一轮的结果。
 */
export interface ReviewOptions {
  strategy?: ContextStrategy | null
  /** 预塞式：最多召回几个文件 */
  maxFiles?: number | null
  /** 预塞式：召回内容的字符总预算 */
  totalBudget?: number | null
  /** agent 式：最多允许模型来回几轮。传 -1 表示不设上限（只用于收敛实验，会一直烧钱） */
  maxRounds?: number | null
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

/**
 * 一个被召回的文件，对应后端 RecalledFile。
 * 后端不返回压缩率（只有 chars 和 rawChars），前端自己算。
 */
export interface RecalledFile {
  path: string
  /** 骨架字符数，实际进入提示词的部分 */
  chars: number
  /** 文件原始字符数 */
  rawChars: number
}

/** 召回预览响应，对应后端 RecallPreview。这条链路不调用模型，所以没有费用 */
export interface RecallPreview {
  /** 符号解析出来的、有资格被召回的文件总数 */
  candidateFiles: number
  files: RecalledFile[]
  contextChars: number
  rawChars: number
  budget: number
  /** 参数签名，例如 f12b15000 */
  signature: string
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
  repo: string
  commitSha: string
  /** 这条结果是用哪版提示词跑出来的 */
  promptVersion: string
  status: TaskStatus
  stage: string | null
  report: ReviewReport | null
  /** 预塞式的上下文证据：这次召回了哪些相关文件。agent 式评审时为空数组 */
  contexts: RecalledFile[]
  /** agent 式的上下文证据：模型自己读代码的全过程。预塞式评审时为空数组 */
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
