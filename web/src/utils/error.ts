import axios from 'axios'

/** 把 axios 的报错抽成一句能直接弹给用户的话 */
export function errorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    return error.message
  }
  return String(error)
}
