import axios from 'axios'
import { TOKEN_KEY, clearSession } from '../auth/auth'

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json',
  },
})

// 公开端点（登录 / 学生注册）不携带历史令牌：
// 上一会话遗留的过期令牌若被带上，后端会在进入控制器前直接以 401「请先登录」拒绝，
// 表现为「首次点击登录失败、第二次才成功」。令牌仍每次请求动态读取，其余端点保持不变。
const PUBLIC_ENDPOINTS = ['/auth/login', '/auth/register/student']

http.interceptors.request.use((config) => {
  const url = config.url || ''
  const isPublic = PUBLIC_ENDPOINTS.some(endpoint => url === endpoint || url.endsWith(endpoint))
  const token = isPublic ? '' : sessionStorage.getItem(TOKEN_KEY)
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  (response) => response.config.rawResponse ? response : response.data,
  (error) => {
    if (error.response?.status === 401 && !error.config?.silentBehaviorEvent) clearSession()
    const apiError = error.response?.data || {
      success: false,
      message: '网络异常，请稍后重试',
      errorCode: 'NETWORK_ERROR',
      data: null,
    }
    return Promise.reject(apiError)
  },
)

export default http
