import http from './http'

// 仅预留文档已经确定的认证 API 调用，不在阶段 1 实现登录业务。
export function login(credentials) {
  return http.post('/auth/login', credentials)
}

export function getCurrentUser() {
  return http.get('/auth/me')
}
