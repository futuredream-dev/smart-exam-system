import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import axios from 'axios'
import { normalizeApiError } from '../src/utils/httpError.js'
async function client() {
  const state = { token: 'current-token', currentUser: { id: 1 } }; let draft = 'unsaved'
  const source = (await readFile(new URL('../src/api/http.js', import.meta.url), 'utf8'))
    .replace(/import\s*[^\n]*from[^\n]*\n/g, '').replace('import.meta.env.VITE_API_BASE_URL', "''").replace('export default http', 'return http')
  const clearSession = () => { state.token = ''; state.currentUser = null; draft = null }
  const expireSession = expected => { if (expected && expected !== state.token) return; state.token = ''; state.currentUser = null }
  const storage = { getItem() { throw Error('storage denied') } }
  const http = new Function('axios', 'normalizeApiError', 'TOKEN_KEY', 'clearSession', 'authState', 'expireSession', 'sessionStorage', source)(axios, normalizeApiError, 'key', clearSession, state, expireSession, storage)
  return { http, state, get draft() { return draft } }
}
test('requests use memory auth when session storage is unavailable', async () => {
  const { http } = await client(); let token
  http.defaults.adapter = async config => { token = config.headers.Authorization; return { config, status: 200, data: { success: true } } }
  assert.deepEqual(await http.get('/student/exams'), { success: true }); assert.equal(token, 'Bearer current-token')
})
test('private 401 clears authentication but retains the recoverable answer draft', async () => {
  const c = await client()
  c.http.defaults.adapter = async config => { throw { config, response: { status: 401, data: {} } } }
  await assert.rejects(c.http.get('/student/exams')); assert.equal(c.state.token, ''); assert.equal(c.draft, 'unsaved')
})
test('login errors and late old-session failures do not expire the current identity', async () => {
  const c = await client()
  c.http.defaults.adapter = async config => { if (config.url !== '/auth/login') c.state.token = 'new-token'; throw { config, response: { status: 401, data: {} } } }
  await assert.rejects(c.http.post('/auth/login')); assert.equal(c.state.token, 'current-token')
  await assert.rejects(c.http.get('/student/exams')); assert.equal(c.state.token, 'new-token')
})
