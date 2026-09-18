import axios from 'axios'

/**
 * /api 下所有请求共用一个实例。
 * 提交和查询都是毫秒级返回，真正耗时的评审在后台跑，所以不需要长超时。
 */
export const http = axios.create({
  baseURL: '/api',
  timeout: 15_000,
})
