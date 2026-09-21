import axios from 'axios'
import { TOKEN_KEY, clearSession } from '../auth/auth'

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json',
  },
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  (response) => response.config.rawResponse ? response : response.data,
  (error) => {
    if (error.response?.status === 401) clearSession()
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
