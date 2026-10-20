import axios from 'axios'
import { normalizeApiError } from '../utils/httpError'
import { authState, expireSession } from '../auth/auth'

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json',
  },
})

// 公开端点（登录 / 学生注册）不携带历史令牌：
// 上一会话遗留的过期令牌若被带上，后端会在进入控制器前直接以 401「请先登录」拒绝，
// 每次请求读取当前内存会话；sessionStorage 不可用时也能正常作答。
const PUBLIC_ENDPOINTS = ['/auth/login', '/auth/register/student']

http.interceptors.request.use((config) => {
  const url = config.url || ''
  const isPublic = PUBLIC_ENDPOINTS.some(endpoint => url === endpoint || url.endsWith(endpoint))
  const token = isPublic ? '' : authState.token
  config.sessionToken = token
  if (token) config.headers.Authorization = `Bearer ${token}`
  else delete config.headers.Authorization
  return config
})

http.interceptors.response.use(
  (response) => response.config.rawResponse ? response : response.data,
  (error) => {
    if (error.response?.status === 401 && error.config?.sessionToken && !error.config?.silentBehaviorEvent) expireSession(error.config.sessionToken)
    const apiError = normalizeApiError(error)
    return Promise.reject(apiError)
  },
)

export default http
