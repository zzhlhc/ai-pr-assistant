import axios from 'axios'

/** 把 axios 的报错抽成一句能直接弹给用户的话 */
export function errorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    // 后端把 Gitee 的报错（比如限流）翻译成了纯文本，直接用原文，比 "status code 500" 有用得多
    const data = error.response?.data
    if (typeof data === 'string' && data.trim()) {
      return data
    }
    return error.message
  }
  return String(error)
}
