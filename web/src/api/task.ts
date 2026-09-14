import axios from 'axios'
import type { RecallPreview, ReviewOptions, ReviewStats, ReviewTask, TaskSummary } from '../types/task'

const http = axios.create({
  baseURL: '/api',
  // 提交和查询都是毫秒级返回，真正耗时的评审在后台跑，所以不需要长超时
  timeout: 15_000,
})

export async function createTask(
  repo: string,
  commitSha: string,
  options?: ReviewOptions,
): Promise<ReviewTask> {
  const { data } = await http.post<ReviewTask>('/tasks', { repo, commitSha, options })
  return data
}

/**
 * 预览召回：只跑"拉 diff + 召回相关代码"，不调用模型。
 * 超时单独放宽到 60 秒 —— 这一步要建仓库索引（约 2 秒）再逐个拉文件，
 * 实测波动能到 15 秒，用默认的 15 秒会把正常的预览掐断。
 */
export async function previewRecall(
  repo: string,
  commitSha: string,
  options?: ReviewOptions,
): Promise<RecallPreview> {
  const { data } = await http.post<RecallPreview>(
    '/context/preview',
    { repo, commitSha, options },
    { timeout: 60_000 },
  )
  return data
}

export async function listTasks(): Promise<TaskSummary[]> {
  const { data } = await http.get<TaskSummary[]>('/tasks')
  return data
}

export async function getStats(): Promise<ReviewStats> {
  const { data } = await http.get<ReviewStats>('/stats')
  return data
}

export async function getTask(id: string): Promise<ReviewTask> {
  const { data } = await http.get<ReviewTask>(`/tasks/${id}`)
  return data
}

/**
 * 订阅任务进度，返回一个取消订阅的函数。
 * 用 SSE 而不是轮询：一次评审 40~90 秒，轮询要么间隔太密打爆后端、
 * 要么太疏让进度看起来卡住，SSE 是服务端推、延迟最低。
 */
export function subscribeTask(
  id: string,
  onTask: (task: ReviewTask) => void,
  onError?: () => void,
): () => void {
  const source = new EventSource(`/api/tasks/${id}/stream`)

  const handle = (event: MessageEvent) => {
    const task = JSON.parse(event.data) as ReviewTask
    onTask(task)
    // 任务结束必须主动关掉。服务端已经把这条流结束了，浏览器检测到连接关闭会
    // 自动重连，而重连会立刻收到重放的最后一条事件再断开，形成死循环。
    if (task.status === 'SUCCESS' || task.status === 'FAILED') {
      source.close()
    }
  }

  source.addEventListener('task', handle)
  source.onerror = () => onError?.()
  return () => source.close()
}
