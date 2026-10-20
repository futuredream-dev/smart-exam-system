export function normalizeApiError(error) {
  const httpStatus = error.response?.status
  if (error.response?.status === 413) return { success: false, message: '上传文件超过允许大小，请按页面文件上限缩小后重试。', errorCode: 'UPLOAD_TOO_LARGE', data: null }
  const payload = error.response?.data
  if (payload && typeof payload === 'object' && typeof payload.message === 'string') return { ...payload, httpStatus }
  const rejection = { 400: ['BAD_REQUEST', '请求未被接受，请检查输入'], 401: ['UNAUTHORIZED', '登录已过期，请重新登录'], 403: ['FORBIDDEN', '当前账号无权执行此操作'], 409: ['CONFLICT', '答卷状态已变化，请刷新确认'] }[httpStatus]
  if (rejection) return { success: false, errorCode: rejection[0], message: rejection[1], httpStatus, data: null }
  if (['ECONNABORTED', 'ETIMEDOUT'].includes(error.code)) return { success: false, message: '请求超时，处理结果尚未确认，请先刷新结果。', errorCode: 'REQUEST_TIMEOUT', data: null }
  return { success: false, message: '网络异常，请稍后重试', errorCode: 'NETWORK_ERROR', data: null }
}
