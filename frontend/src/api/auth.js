import http from './http'

// 仅预留文档已经确定的认证 API 调用，不在阶段 1 实现登录业务。
export function login(credentials) {
  return http.post('/auth/login', credentials)
}

export function registerStudent(data) {
  return http.post('/auth/register/student', data)
}

export function getCurrentUser() {
  return http.get('/auth/me')
}

export function changePassword(data) {
  return http.put('/auth/password', data)
}
