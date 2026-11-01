import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { parse, compileScript } from 'vue/compiler-sfc'
import { computed, reactive, ref, watch } from 'vue'
import { createAnswerSaveQueue, readDraft, writeDraft, clearDraft, clearUserDrafts } from '../src/utils/studentAnswerDraft.js'
import { remainingMillis } from '../src/utils/examClock.js'
import { safeReturnPath } from '../src/utils/returnPath.js'
import { renderView, modal } from './helpers/viewHarness.mjs'
function storage() { const m = new Map(); return { getItem: k => m.get(k) ?? null, setItem: (k, v) => m.set(k, v), removeItem: k => m.delete(k), key: index => [...m.keys()][index], get length() { return m.size } } }
const settle = async () => { for (let n = 0; n < 12; n++) await Promise.resolve() }
function deferred() { let resolve, reject; const promise = new Promise((ok, fail) => { resolve = ok; reject = fail }); return { promise, resolve, reject } }
async function page(options = {}) {
  const source = await readFile(new URL('../src/views/student/StudentPaperView.vue', import.meta.url), 'utf8')
  const compiled = compileScript(parse(source).descriptor, { id: 'student-paper', genDefaultAs: 'component' }).content.replace(/import\s*[\s\S]*?from\s*['"][^'"]+['"];?/g, '')
  const server = { submissionId: 10, examId: 7, title: '测试考试', status: 'IN_PROGRESS', endTime: '2026-10-20T11:00:00', serverNow: '2026-10-20T02:00:00Z', deadlineAt: '2026-10-20T03:00:00Z', questions: [1, 2].map(id => ({ id, answerContent: '', questionType: 'SHORT_ANSWER', content: '题目', score: 10 })) }
  const saved = [], submits = [], navigation = [], listeners = {}, unmount = [], timers = new Map(); let timerId = 0, leave
  const store = options.storage ?? storage()
  let auth
  if (options.realAuth) {
    const authCode = (await readFile(new URL('../src/auth/auth.js', import.meta.url), 'utf8')).replace(/import\s*[^\n]*from[^\n]*\n/g, '').replace(/export /g, '')
    auth = new Function('reactive', 'clearUserDrafts', 'sessionStorage', `${authCode};return {authState,setSession,clearSession}`)(reactive, clearUserDrafts, store)
    auth.setSession('synthetic', { id: 1 })
  }
  const state = auth?.authState ?? reactive({ token: 'synthetic', currentUser: { id: 1 }, reauthenticationRequired: false })
  const api = { paper: async () => ({ data: structuredClone(server) }),
    save: async (id, q, value) => { saved.push([q, value]); if (options.fail === q) throw { errorCode: 'BAD_REQUEST', message: '保存失败' }; server.questions.find(row => row.id === q).answerContent = value; return { data: structuredClone(server) } },
    submit: async () => { submits.push(10); server.status = 'SUBMITTED' }, exam: async () => ({ data: { submissionStatus: 'AUTO_SUBMITTED', lifecycleStatus: 'PENDING_REVIEW' } }), reportExamBehaviorEvent: async () => {}, ...options.api }
  const bindings = { computed, reactive, ref, watch, onMounted: () => {}, onBeforeUnmount: fn => unmount.push(fn), onBeforeRouteLeave: fn => { leave = fn },
    useRoute: () => ({ params: { submissionId: 10 }, fullPath: '/student/paper/10' }), useRouter: () => ({ push: async value => navigation.push(value), replace: async value => navigation.push(value) }),
    studentApi: api, formatTime: value => value, authState: state, safeReturnPath, remainingMillis, TeacherModal: {},
    createAnswerSaveQueue: opts => createAnswerSaveQueue({ ...opts, schedule: (fn, delay) => { timers.set(++timerId, { fn, delay }); return timerId }, cancel: id => timers.delete(id) }),
    readDraft: (user, id) => readDraft(user, id, store), writeDraft: (user, id, answers) => writeDraft(user, id, answers, store), clearDraft: (user, id) => clearDraft(user, id, store), sessionStorage: store, performance: { now: options.monotonic ?? (() => 0) },
    document: { visibilityState: 'visible', addEventListener: (name, fn) => { listeners[name] = fn }, removeEventListener: name => { delete listeners[name] } },
    window: { addEventListener: (name, fn) => { listeners[name] = fn }, removeEventListener: name => { delete listeners[name] } },
    setInterval: () => 1, clearInterval: () => {}, setTimeout: (fn, delay) => { timers.set(++timerId, { fn, delay }); return timerId }, clearTimeout: id => timers.delete(id),
  }
  const component = new Function(...Object.keys(bindings), `${compiled}; return component`)(...Object.values(bindings))
  const s = component.setup({}, { expose() {} })
  return { s, server, api, saved, submits, navigation, state, auth, store, timers, leave: (...args) => leave(...args), dispose: () => unmount.forEach(fn => fn()) }
}
test('draft restoration is offered only after successful server validation and never silently replaces answers', async () => {
  const p = await page(); writeDraft(1, 10, { 2: { value: 'local', revision: 2 } }, p.store)
  await p.s.load(); assert.equal(p.s.drafts[2], ''); assert.ok(p.s.recovery.value)
  p.s.restoreLocalDraft(); assert.equal(p.s.drafts[2], 'local'); await p.s.queue.flush(); assert.deepEqual(p.saved, [[2, 'local']]); p.dispose()
})
test('a terminal server paper never offers or sends a stored draft', async () => {
  const p = await page({ api: { paper: async () => { throw { errorCode: 'CONFLICT', message: '已交卷' } } } })
  writeDraft(1, 10, { 2: { value: 'local', revision: 1 } }, p.store); await p.s.load()
  assert.equal(p.s.recovery?.value ?? null, null); assert.equal(p.saved.length, 0); p.dispose()
})
test('submit waits for a pending latest answer, blocks repeated clicks, and cleans only this draft', async () => {
  const pending = deferred(), p = await page({ api: { save: () => pending.promise } }); await p.s.load()
  p.s.editAnswer(1, 'new answer'); const submit = p.s.submit(); await settle(); await p.s.submit()
  assert.equal(p.submits.length, 0); assert.equal(p.s.busy.value, true)
  pending.resolve({ data: p.server }); await submit; assert.equal(p.submits.length, 1); assert.equal(readDraft(1, 10, p.store), null)
  assert.equal(p.navigation.length, 1); p.dispose()
})
test('one failed answer prevents submit and preserves input for explicit retry', async () => {
  const options = { fail: 2 }, p = await page(options); await p.s.load(); p.s.editAnswer(1, 'a'); p.s.editAnswer(2, 'b')
  await p.s.submit(); assert.equal(p.submits.length, 0); assert.equal(p.s.drafts[2], 'b'); assert.match(p.s.error.value, /保存/)
  options.fail = null; p.s.save({ id: 2 }); await p.s.submit(); assert.equal(p.submits.length, 1); p.dispose()
})
test('401 pauses all queued writes, keeps drafts and exposes reauthentication', async () => {
  let calls = 0; const p = await page({ api: { save: async () => { calls++; throw { errorCode: 'UNAUTHORIZED', message: '登录过期' } } } }); await p.s.load()
  p.s.editAnswer(1, 'a'); await p.s.submit(); assert.equal(p.submits.length, 0); assert.equal(calls, 1)
  assert.equal(p.s.sessionExpired.value, true); assert.ok(readDraft(1, 10, p.store)); await assert.rejects(p.s.queue.flush()); p.dispose()
})
test('late save response never overwrites input edited during that request', async () => {
  const first = deferred(); let number = 0
  const p = await page({ api: { save: async () => ++number === 1 ? first.promise : { data: p.server } } }); await p.s.load()
  p.s.editAnswer(1, 'first'); const saving = p.s.queue.flush(); await settle(); p.s.editAnswer(1, 'newer'); first.resolve({ data: p.server })
  await saving; assert.equal(p.s.drafts[1], 'newer'); assert.equal(p.s.queue.getState(1).status, 'saved'); p.dispose()
})
test('storage failure warns while editing and saving remain usable', async () => {
  const denied = { getItem() { throw Error('denied') }, setItem() { throw Error('quota') }, removeItem() { throw Error('denied') } }
  const p = await page({ storage: denied }); await p.s.load(); p.s.editAnswer(1, 'answer'); await p.s.queue.flush()
  assert.equal(p.s.drafts[1], 'answer'); assert.match(p.s.storageNotice.value, /草稿|存储/); assert.equal(p.saved.length, 1); p.dispose()
})
test('dirty route departure requires explicit confirmation and a canceled departure preserves the queue', async () => {
  const p = await page(); await p.s.load(); p.s.editAnswer(1, 'unsaved')
  const departure = p.leave({ fullPath: '/student' }); await settle(); assert.equal(p.s.leaveOpen.value, true)
  p.s.resolveLeave(false); assert.equal(await departure, false); assert.equal(p.navigation.length, 0); p.dispose()
})
test('deadline freezes editing, queries server state and retains unsaved count instead of navigating away', async () => {
  const p = await page(); await p.s.load(); p.s.editAnswer(1, 'unsaved'); await p.s.checkDeadline(true)
  assert.equal(p.s.expired.value, true); assert.match(p.s.receipt.value, /自动交卷/); assert.equal(p.navigation.length, 0)
  const before = p.s.drafts[1]; p.s.editAnswer(1, 'late'); assert.equal(p.s.drafts[1], before); assert.equal(p.saved.length, 0); p.dispose()
})
test('a late submit acknowledgement after identity switch must not navigate the new session', async () => {
  const pending = deferred(), p = await page({ api: { submit: () => pending.promise } }); await p.s.load()
  const submitting = p.s.submit(); await settle(); p.state.currentUser = { id: 2 }; p.state.token = 'new-token'
  pending.resolve(); await submitting; assert.equal(p.navigation.length, 0); p.dispose()
})
test('minimum-submit-time conflict leaves a still-active paper editable', async () => {
  const p = await page({ api: { submit: async () => { throw { errorCode: 'CONFLICT', message: '未到最早交卷时间' } },
    exam: async () => ({ data: { submissionStatus: 'IN_PROGRESS', serverNow: '2026-10-20T02:00:10Z', deadlineAt: '2026-10-20T03:00:00Z' } }) } })
  await p.s.load(); await p.s.submit(); assert.equal(p.s.expired.value, false); assert.equal(p.s.locked.value, false)
  p.s.editAnswer(1, 'continue'); await p.s.queue.flush(); assert.equal(p.saved.length, 1); assert.match(p.s.error.value, /最早交卷/); p.dispose()
})
test('reverting input to its old baseline during a save still retains a dirty recovery draft', async () => {
  const pending = deferred(), p = await page({ api: { save: () => pending.promise } }); await p.s.load()
  p.s.editAnswer(1, 'temporary'); const saving = p.s.queue.flush().catch(() => {}); await settle(); p.s.editAnswer(1, '')
  try { assert.equal(p.s.unsavedCount.value, 1); assert.equal(readDraft(1, 10, p.store).answers[1].value, '') }
  finally { p.dispose(); pending.resolve({ data: p.server }); await saving }
})
test('explicit logout while the page is mounted cannot recreate the deleted draft through a synchronous watcher', async () => {
  const p = await page({ realAuth: true }); await p.s.load(); p.s.editAnswer(1, 'private answer'); assert.ok(readDraft(1, 10, p.store))
  p.auth.clearSession(); await settle(); assert.equal(readDraft(1, 10, p.store), null); p.dispose()
})
test('a delayed newer timestamp cannot move the projected countdown backwards', async () => {
  let mono = 0; const p = await page({ monotonic: () => mono }); await p.s.load(); mono = 10000; await p.s.checkDeadline()
  const before = p.s.remainingSeconds.value; p.api.paper = async () => ({ data: { ...p.server, serverNow: '2026-10-20T02:00:01Z' } })
  await p.s.refreshStatus(); assert.ok(p.s.remainingSeconds.value <= before); p.dispose()
})
test('a delayed response near the deadline cannot postpone freezing or the server status check', async () => {
  let mono = 0; const p = await page({ monotonic: () => mono }); p.server.deadlineAt = '2026-10-20T02:00:05Z'; await p.s.load(); mono = 10000
  p.api.paper = async () => ({ data: { ...p.server, serverNow: '2026-10-20T02:00:01Z' } })
  await p.s.refreshStatus(); assert.equal(p.s.expired.value, true); assert.match(p.s.receipt.value, /自动交卷/); p.dispose()
})
test('uncertain in-flight draft still requires explicit restoration when current server values happen to match', async () => {
  const p = await page(); writeDraft(1, 10, { 1: { value: '', revision: 2, uncertain: true } }, p.store)
  await p.s.load(); assert.ok(p.s.recovery.value); p.s.restoreLocalDraft(); await p.s.queue.flush(); assert.deepEqual(p.saved, [[1, '']]); p.dispose()
})
test('an uncertain submit result is checked once without resubmitting and shows a confirmed receipt', async () => {
  let requests = 0, checks = 0; const p = await page({ api: { submit: async () => { requests++; throw { errorCode: 'REQUEST_TIMEOUT', message: '结果尚未确认' } }, exam: async () => { checks++; return { data: { submissionStatus: 'SUBMITTED' } } } } })
  await p.s.load(); await p.s.submit(); assert.equal(requests, 1); assert.equal(checks, 1); assert.equal(p.s.terminal.value, true); assert.match(p.s.receipt.value, /已提交/); assert.equal(p.s.error.value, ''); p.dispose()
})
test('an unsuccessful status query keeps the uncertain-submit error until confirmation is available', async () => {
  const p = await page({ api: { submit: async () => { throw { errorCode: 'REQUEST_TIMEOUT', message: '结果尚未确认' } }, exam: async () => { throw { errorCode: 'NETWORK_ERROR' } } } })
  await p.s.load(); await p.s.submit()
  assert.equal(p.s.terminal.value, false); assert.match(p.s.error.value, /尚未确认/); assert.match(p.s.receipt.value, /无法确认/); p.dispose()
})
test('a fresh server timestamp after slow initial processing must not expire the paper early', async () => {
  let mono = 0; const pending = deferred(), p = await page({ monotonic: () => mono, api: { paper: () => pending.promise } })
  p.server.deadlineAt = '2026-10-20T02:00:05Z'; const loading = p.s.load(); mono = 4000
  pending.resolve({ data: { ...p.server, serverNow: '2026-10-20T02:00:04Z' } }); await loading
  assert.equal(p.s.expired.value, false); assert.equal(p.s.locked.value, false)
  assert.equal(p.s.remainingSeconds.value, 1); p.dispose()
})

test('a slow save does not add processing time twice to the returned server timestamp', async () => {
  let mono = 0; const pending = deferred(), p = await page({ monotonic: () => mono, api: { save: () => pending.promise } })
  p.server.deadlineAt = '2026-10-20T02:00:20Z'; await p.s.load(); p.s.editAnswer(1, 'answer')
  const saving = p.s.queue.flush(); await settle(); mono = 12000
  pending.resolve({ data: { ...p.server, serverNow: '2026-10-20T02:00:12Z' } }); await saving; await p.s.checkDeadline()
  assert.equal(p.s.remainingSeconds.value, 8); assert.equal(p.s.locked.value, false); p.dispose()
})

test('an authoritative active status corrects an ahead estimate and keeps editing enabled on the next tick', async () => {
  let mono = 0; const p = await page({ monotonic: () => mono, api: { exam: async () => ({ data: {
    submissionStatus: 'IN_PROGRESS', serverNow: '2026-10-20T02:00:02Z', deadlineAt: '2026-10-20T02:00:20Z',
  } }) } })
  p.server.deadlineAt = '2026-10-20T02:00:20Z'; await p.s.load()
  p.s.paper.value.serverNow = '2026-10-20T02:00:19Z'; mono = 2000; await p.s.checkDeadline()
  assert.equal(p.s.expired.value, false); assert.equal(p.s.locked.value, false); assert.equal(p.s.remainingSeconds.value, 18)
  mono = 3000; await p.s.checkDeadline(); assert.equal(p.s.expired.value, false)
  p.s.editAnswer(1, 'still editable'); await p.s.queue.flush(); assert.deepEqual(p.saved, [[1, 'still editable']]); p.dispose()
})

test('an active status without valid time remaining cannot reopen a frozen paper', async () => {
  for (const timing of [{}, { serverNow: '2026-10-20T02:00:20Z', deadlineAt: '2026-10-20T02:00:20Z' }]) {
    const p = await page({ api: { exam: async () => ({ data: { submissionStatus: 'IN_PROGRESS', ...timing } }) } })
    await p.s.load(); await p.s.checkDeadline(true)
    assert.equal(p.s.expired.value, true); assert.equal(p.s.locked.value, true)
    p.s.editAnswer(1, 'late'); assert.equal(p.s.drafts[1], ''); p.dispose()
  }
})

test('the open leave dialog explains that saving completed instead of claiming zero unsaved answers', async () => {
  const p = await page(); await p.s.load(); p.s.editAnswer(1, 'answer'); const leaving = p.leave()
  try {
    const before = await renderView('views/student/StudentPaperView.vue', { ...p.s, TeacherModal: modal })
    assert.match(before, /还有答案未保存/); assert.match(before, /仍有 1 题/)
    await p.s.queue.flush()
    const after = await renderView('views/student/StudentPaperView.vue', { ...p.s, TeacherModal: modal })
    assert.match(after, /答案已保存/); assert.doesNotMatch(after, /还有答案未保存|仍有 0 题/)
  } finally { p.s.resolveLeave(false); await leaving; p.dispose() }
})
