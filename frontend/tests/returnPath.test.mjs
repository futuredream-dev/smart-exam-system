import test from 'node:test'
import assert from 'node:assert/strict'
const module = await import('../src/utils/returnPath.js').catch(() => ({}))
const safe = value => { assert.equal(typeof module.safeReturnPath, 'function', 'safe return path not implemented'); return module.safeReturnPath(value) }
test('safe return paths preserve internal routes and queries', () => {
  assert.equal(safe('/student/paper/10?from=exam#q2'), '/student/paper/10?from=exam#q2'); assert.equal(safe('/teacher/exams'), '/teacher/exams')
})
test('external URLs, encoded URL separators, controls and login loops are rejected', () => {
  for (const value of [null, [], 'https://evil.test', '//evil.test', '/\\evil.test', '/%2fevil.test', '/%252fevil.test', '/%5cevil.test', '/student\n', '/login', '/login?returnTo=/student', '/l%6fgin', '/student/../login', '/login/', '/%ZZ']) assert.equal(safe(value), null, String(value))
})
