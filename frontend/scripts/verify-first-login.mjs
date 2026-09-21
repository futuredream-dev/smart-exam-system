/**
 * 封板缺陷回归：首次冷启动登录「请先登录」
 *
 * 真实浏览器（Microsoft Edge headless + CDP）验证三种角色的登录链路：
 *   用例 A  冷启动（无历史令牌）→ 输入正确凭据 → 只点击一次登录 → 必须直接进入首页
 *   用例 B  冷启动（sessionStorage 残留当前标签页上一会话的过期令牌，真实用户场景）
 *           → 输入正确凭据 → 只点击一次登录 → 必须直接进入首页
 *
 * 断言（每个用例）：
 *   - POST /api/auth/login 返回 200
 *   - 最终路由 = 角色首页（/teacher、/student、/admin）
 *   - 页面不出现「请先登录」
 *   - 登录后 token storage = SET
 *   - 首页首条业务接口 = 200 且携带 Authorization
 *
 * 只读业务数据：仅登录与读取首页工作台，不创建 / 不修改任何业务数据。
 * 不输出任何账号口令、JWT 内容与 Secret。
 *
 * 用法：
 *   node scripts/verify-first-login.mjs [--rounds 3]
 *   P7_ADMIN_USER=... P7_ADMIN_PASS=... node scripts/verify-first-login.mjs
 */
import assert from 'node:assert/strict'
import child from 'node:child_process'
import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const web = process.env.P7_WEB || 'http://localhost:5173'
const api = process.env.P7_API || 'http://127.0.0.1:8080/api'
const roundsArg = process.argv.indexOf('--rounds')
const rounds = roundsArg > -1 ? Number(process.argv[roundsArg + 1]) : 3

const TOKEN_KEY = 'smart_exam_token'

/** 读取 config/local.env（键值仅内部使用，不打印）。 */
function localEnv() {
  const values = { ...process.env }
  const file = path.join(root, 'config', 'local.env')
  if (!fs.existsSync(file)) return values
  for (const raw of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const line = raw.trim()
    if (!line || line.startsWith('#')) continue
    const at = line.indexOf('=')
    if (at < 1) continue
    const key = line.slice(0, at).trim()
    let value = line.slice(at + 1).trim()
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) value = value.slice(1, -1)
    if (!values[key]) values[key] = value
  }
  return values
}
const env = localEnv()

const accounts = {
  teacher: { username: process.env.P7_TEACHER_USER || 'teacher_demo', password: process.env.P7_TEACHER_PASS || 'password', home: '/teacher', biz: '/api/teacher/dashboard' },
  student: { username: process.env.P7_STUDENT_USER || 'student_demo_1', password: process.env.P7_STUDENT_PASS || 'password', home: '/student', biz: '/api/student/exams' },
  admin: { username: process.env.P7_ADMIN_USER || env.ADMIN_USERNAME, password: process.env.P7_ADMIN_PASS || env.ADMIN_INITIAL_PASSWORD, home: '/admin', biz: '/api/admin/teachers' },
}

/**
 * 生成一枚「上一会话遗留的过期令牌」：HS256、与后端同密钥、exp 已过期。
 * 仅用于复现真实场景（HTTP 有效期 2 小时，过期后仍留在当前标签页 sessionStorage）。
 * 密钥仅用于本地签名计算，不写入日志。
 */
function expiredToken(secret, username) {
  const b64 = value => Buffer.from(typeof value === 'string' ? value : JSON.stringify(value)).toString('base64url')
  const now = Math.floor(Date.now() / 1000)
  const header = b64({ alg: 'HS256', typ: 'JWT' })
  const payload = b64({ sub: username, userId: 1, role: 'TEACHER', iat: now - 10800, exp: now - 3600 })
  const signature = crypto.createHmac('sha256', secret).update(`${header}.${payload}`).digest('base64url')
  return `${header}.${payload}.${signature}`
}

const XHR_LOGGER = `(() => {
  window.__reqLog = []
  const XO = XMLHttpRequest.prototype.open, XS = XMLHttpRequest.prototype.send, XH = XMLHttpRequest.prototype.setRequestHeader
  XMLHttpRequest.prototype.open = function (m, u) { this.__rec = { m: String(m), u: String(u), h: {}, status: null }; return XO.apply(this, arguments) }
  XMLHttpRequest.prototype.setRequestHeader = function (k, v) { if (this.__rec) this.__rec.h[String(k).toLowerCase()] = String(v); return XH.apply(this, arguments) }
  XMLHttpRequest.prototype.send = function () {
    const rec = this.__rec
    if (rec) { window.__reqLog.push(rec); this.addEventListener('loadend', () => { rec.status = this.status }) }
    return XS.apply(this, arguments)
  }
  const OF = window.fetch
  window.fetch = function (input, init) {
    const rec = { m: (init && init.method) || 'GET', u: String(input && input.url ? input.url : input), h: {}, status: null }
    window.__reqLog.push(rec)
    return OF.apply(this, arguments).then(r => { rec.status = r.status; return r })
  }
})()`

class Cdp {
  constructor(url) { this.id = 0; this.pending = new Map(); this.exceptions = []; this.ws = new WebSocket(url); this.ws.onmessage = e => this.message(JSON.parse(e.data)) }
  async open() { if (this.ws.readyState === WebSocket.OPEN) return; await new Promise((resolve, reject) => { this.ws.onopen = resolve; this.ws.onerror = reject }) }
  message(value) {
    if (value.id) { const p = this.pending.get(value.id); if (!p) return; this.pending.delete(value.id); value.error ? p.reject(new Error(value.error.message)) : p.resolve(value.result); return }
    if (value.method === 'Runtime.exceptionThrown') this.exceptions.push(value.params?.exceptionDetails?.exception?.description || value.params?.exceptionDetails?.text || 'unknown')
  }
  send(method, params = {}) { const id = ++this.id; this.ws.send(JSON.stringify({ id, method, params })); return new Promise((resolve, reject) => this.pending.set(id, { resolve, reject })) }
  close() { this.ws.close() }
}
const sleep = ms => new Promise(r => setTimeout(r, ms))
async function evaluate(cdp, expression) { return (await cdp.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })).result.value }
async function waitFor(cdp, expression, timeout = 15000) {
  const end = Date.now() + timeout
  while (Date.now() < end) {
    if (await evaluate(cdp, expression)) return true
    await sleep(100)
  }
  return false
}
async function navigate(cdp, url) { await cdp.send('Page.navigate', { url }); await waitFor(cdp, `document.readyState==='complete'`); await sleep(250) }

const setValue = (selector, index, value) => `(()=>{const el=document.querySelectorAll(${JSON.stringify(selector)})[${index}];const s=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;s.call(el,${JSON.stringify(value)});el.dispatchEvent(new Event('input',{bubbles:true}));return true})()`

/** 执行一次「只点击一次登录」的完整用例。 */
async function loginCase(cdp, key, stale, secret) {
  const account = accounts[key]
  const record = { role: key.toUpperCase(), mode: stale ? 'stale-token' : 'cold-start' }

  await navigate(cdp, `${web}/login`)
  await evaluate(cdp, `sessionStorage.clear()`)
  if (stale) {
    const token = expiredToken(secret, account.username)
    await evaluate(cdp, `sessionStorage.setItem(${JSON.stringify(TOKEN_KEY)}, ${JSON.stringify(token)})`)
  }
  await navigate(cdp, `${web}/login`)
  await waitFor(cdp, `document.querySelectorAll('form.login-form input').length>=2`)
  record.tokenBeforeLogin = await evaluate(cdp, `sessionStorage.getItem(${JSON.stringify(TOKEN_KEY)}) ? 'SET' : 'NOT SET'`)
  await evaluate(cdp, `window.__reqLog = []`)

  // 只点击一次登录
  await evaluate(cdp, setValue('form.login-form input', 0, account.username))
  await evaluate(cdp, setValue('form.login-form input', 1, account.password))
  await evaluate(cdp, `document.querySelector('form.login-form button[type=submit]').click()`)

  const landed = await waitFor(cdp, `location.pathname===${JSON.stringify(account.home)}`, 20000)
  await sleep(900)

  const log = await evaluate(cdp, `window.__reqLog.map(r=>({m:r.m,u:r.u,status:r.status,auth:!!r.h.authorization}))`)
  const login = log.find(r => r.u.includes('/api/auth/login'))
  const biz = log.filter(r => r.u.includes(account.biz))
  const state = await evaluate(cdp, `({path:location.pathname, text:document.body.innerText, token:sessionStorage.getItem(${JSON.stringify(TOKEN_KEY)})?'SET':'NOT SET'})`)

  record.loginStatus = login ? login.status : null
  record.loginAuthorization = login ? login.auth : null
  record.landedPath = state.path
  record.tokenAfterLogin = state.token
  record.firstBizStatus = biz.length ? biz[0].status : null
  record.firstBizAuthorization = biz.length ? biz[0].auth : null
  record.showedPleaseLogin = state.text.includes('请先登录')
  record.noticeText = (state.text.match(/请先登录|登录失败|用户名或密码错误|账号已停用/g) || [])[0] || null
  record.failedRequests = log.filter(r => r.status === 401 || r.status === 403).map(r => `${r.m} ${r.u} -> ${r.status}`)

  const failures = []
  const check = (condition, message) => { if (!condition) failures.push(message) }
  check(landed, `未能进入 ${account.home}（当前 ${state.path}）`)
  check(record.loginStatus === 200, `首次点击登录必须 200，实际 ${record.loginStatus}`)
  check(record.showedPleaseLogin === false, '页面不应出现「请先登录」')
  check(record.tokenAfterLogin === 'SET', '登录后 token storage 应为 SET')
  check(biz.length > 0, '首页应发出业务请求')
  check(record.firstBizStatus === 200, `首条业务请求应 200，实际 ${record.firstBizStatus}`)
  check(record.firstBizAuthorization === true, '首条业务请求应携带 Authorization')
  if (failures.length) record.failure = failures.join('；')
  return record
}

async function oneRound(roundIndex, secret) {
  const edge = process.env.P7_EDGE || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), `p7-login-r${roundIndex}-`))
  const port = 9350 + roundIndex
  const edgeProcess = child.spawn(edge, ['--headless=new', `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`, '--no-first-run', '--disable-gpu', 'about:blank'], { stdio: 'ignore' })
  let cdp
  const records = []
  try {
    let target
    for (let i = 0; i < 80 && !target; i++) {
      try { target = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json().then(rows => rows.find(r => r.type === 'page')) } catch {}
      if (!target) await sleep(120)
    }
    assert.ok(target, 'Edge DevTools target 不可用')
    cdp = new Cdp(target.webSocketDebuggerUrl)
    await cdp.open()
    await cdp.send('Page.enable')
    await cdp.send('Runtime.enable')
    await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: XHR_LOGGER })

    const run = async (key, stale) => {
      try { records.push(await loginCase(cdp, key, stale, secret)) }
      catch (error) { records.push({ role: key.toUpperCase(), mode: stale ? 'stale-token' : 'cold-start', failure: error.message }) }
    }
    await run('teacher', false)
    await run('teacher', true)
    await run('student', true)
    await run('admin', true)
    return { round: roundIndex, records, exceptions: cdp.exceptions }  } finally {
    if (cdp) cdp.close()
    edgeProcess.kill()
  }
}

async function main() {
  assert.ok(env.JWT_SECRET, '缺少 JWT_SECRET（config/local.env），无法构造过期令牌用例')
  assert.ok(accounts.admin.username && accounts.admin.password, '缺少可用的 ADMIN 凭据，请通过 P7_ADMIN_USER/P7_ADMIN_PASS 提供临时 fixture')
  const all = []
  for (let index = 1; index <= rounds; index++) {
    const result = await oneRound(index, env.JWT_SECRET)
    all.push(result)
    console.log(`ROUND ${index}${result.records.some(r => r.failure) ? ' FAIL' : ' PASS'}`)
    for (const record of result.records) {
      console.log('  ' + JSON.stringify(record))
    }
    assert.deepEqual(result.exceptions, [], `第 ${index} 轮出现运行时异常`)
  }
  const broken = all.flatMap(r => r.records.filter(record => record.failure))
  const first = all[0].records.find(r => r.role === 'TEACHER' && r.mode === 'stale-token') || {}
  console.log('EVIDENCE ' + JSON.stringify({
    staleTokenLoginStatus: first.loginStatus,
    staleTokenLoginAuthorization: first.loginAuthorization,
    staleTokenNotice: first.noticeText,
    staleTokenFailedRequests: first.failedRequests,
  }))
  if (broken.length) {
    throw new Error(`${broken.length} 个用例未通过：` + broken.map(r => `${r.role}/${r.mode} -> ${r.failure}`).join(' | '))
  }
  console.log(`PASS first-login regression: ${rounds} 轮 × ${all[0].records.length} 用例（TEACHER 冷启动/残留令牌、STUDENT、ADMIN）`)
}

main().catch(error => {
  console.error('FAIL first-login regression: ' + error.message)
  process.exitCode = 1
})
