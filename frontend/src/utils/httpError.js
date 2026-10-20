export function normalizeApiError(error) {
  if (error.response?.status === 413) return { success: false, message: '上传文件超过允许大小，请按页面文件上限缩小后重试。', errorCode: 'UPLOAD_TOO_LARGE', data: null }
  const payload = error.response?.data
  if (payload && typeof payload === 'object' && typeof payload.message === 'string') return payload
  if (['ECONNABORTED', 'ETIMEDOUT'].includes(error.code)) return { success: false, message: '请求超时，处理结果尚未确认，请先刷新结果。', errorCode: 'REQUEST_TIMEOUT', data: null }
  return { success: false, message: '网络异常，请稍后重试', errorCode: 'NETWORK_ERROR', data: null }
}
