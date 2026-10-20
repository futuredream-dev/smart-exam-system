import test from 'node:test'
import assert from 'node:assert/strict'
const module = await import('../src/utils/httpError.js').catch(() => ({}))
function normalize(error) { assert.equal(typeof module.normalizeApiError, 'function', 'HTTP error normalization not implemented'); return module.normalizeApiError(error) }
test('a proxy HTML 413 becomes a readable upload error without exposing HTML', () => {
  const result = normalize({ response: { status: 413, data: '<html>private proxy internals</html>' } })
  assert.match(result.message, /文件|上传/); assert.doesNotMatch(result.message, /html|private/)
})
test('timeouts clearly identify an unknown result rather than claiming the request failed', () => {
  const result = normalize({ code: 'ECONNABORTED' })
  assert.equal(result.errorCode, 'REQUEST_TIMEOUT'); assert.match(result.message, /超时/)
})
test('structured business validation messages remain usable', () => {
  assert.equal(normalize({ response: { status: 400, data: { success: false, message: '题库不能超过1MB', errorCode: 'BAD_REQUEST' } } }).message, '题库不能超过1MB')
})
test('unstructured authentication and conflict errors retain non-retryable status', () => {
  for (const [status, errorCode] of [[401, 'UNAUTHORIZED'], [403, 'FORBIDDEN'], [409, 'CONFLICT']]) {
    const result = normalize({ response: { status, data: '<html>proxy</html>' } })
    assert.equal(result.errorCode, errorCode); assert.equal(result.httpStatus, status)
  }
})
