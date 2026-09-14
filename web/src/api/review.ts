import axios from 'axios'
import type { ReviewReport, ReviewRequest } from '../types/review'

const http = axios.create({
  baseURL: '/api',
  // 模型一次评审要 40~90 秒，前端超时必须大于后端，否则后端还在跑前端先断了
  timeout: 300_000,
})

export async function submitReview(payload: ReviewRequest): Promise<ReviewReport> {
  const { data } = await http.post<ReviewReport>('/review', payload)
  return data
}
