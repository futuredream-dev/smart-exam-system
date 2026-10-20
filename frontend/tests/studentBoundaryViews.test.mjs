import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { parse, compileScript } from 'vue/compiler-sfc'
import { computed, ref } from 'vue'
import { createBoundaryRefresh } from '../src/utils/examClock.js'
const settle = async () => { for (let n = 0; n < 12; n++) await Promise.resolve() }
async function page(name) {
  const source = await readFile(new URL(`../src/views/student/${name}.vue`, import.meta.url), 'utf8')
  const code = compileScript(parse(source).descriptor, { id: name, genDefaultAs: 'component' }).content.replace(/import\s*[\s\S]*?from\s*['"][^'"]+['"];?/g, '')
  const data = { id: 7, lifecycleStatus: 'NOT_STARTED', serverNow: '2026-10-20T02:00:00Z', startsAt: '2026-10-20T02:00:01Z', entryDeadlineAt: '2026-10-20T02:10:00Z', deadlineAt: '2026-10-20T03:00:00Z' }
  const mounted = [], unmount = [], listeners = {}, timers = new Map(); let next = 0, calls = 0, fail = false
  const read = async () => { calls++; if (fail) throw Error('offline'); return { data: name === 'StudentHomeView' ? [{ ...data }] : { ...data } } }
  const doc = { visibilityState: 'visible', addEventListener: (key, fn) => { listeners[key] = fn }, removeEventListener: key => { delete listeners[key] } }
  const bindings = { computed, ref, onMounted: fn => mounted.push(fn), onBeforeUnmount: fn => unmount.push(fn), useRouter: () => ({}), useRoute: () => ({ params: { examId: 7 }, query: {} }),
    studentApi: { exams: read, exam: read }, formatTime: v => v, lifecycleMeta: () => ({}), document: doc, sessionStorage: { getItem: () => null },
    createBoundaryRefresh: options => createBoundaryRefresh({ ...options, schedule: (fn, delay) => { timers.set(++next, { fn, delay }); return next }, cancel: id => timers.delete(id) }) }
  const component = new Function(...Object.keys(bindings), `${code};return component`)(...Object.values(bindings)), s = component.setup({}, { expose() {} })
  for (const mount of mounted) await mount(); await settle()
  return { s, data, timers, listeners, doc, get calls() { return calls }, set fail(value) { fail = value }, dispose: () => unmount.forEach(fn => fn()) }
}
for (const name of ['StudentHomeView', 'StudentExamDetailView']) {
  test(`${name} refreshes at start, on visibility return and manually, then cleans timers`, async () => {
    const p = await page(name); assert.equal(p.timers.size, 1)
    p.data.lifecycleStatus = 'AVAILABLE'; const timer = [...p.timers.values()][0]; p.timers.clear(); timer.fn(); await settle()
    assert.equal(name === 'StudentHomeView' ? p.s.exams.value[0].lifecycleStatus : p.s.exam.value.lifecycleStatus, 'AVAILABLE')
    const before = p.calls; p.doc.visibilityState = 'hidden'; p.listeners.visibilitychange(); await settle(); assert.equal(p.calls, before)
    p.doc.visibilityState = 'visible'; p.listeners.visibilitychange(); await settle(); assert.equal(p.calls, before + 1)
    await p.s.refresh(); assert.equal(p.calls, before + 2); p.dispose(); assert.equal(p.timers.size, 0); assert.equal(p.listeners.visibilitychange, undefined)
  })
  test(`${name} keeps previous data and sync timestamp when refresh fails`, async () => {
    const p = await page(name), previous = p.s.lastSync?.value; p.fail = true
    assert.equal(typeof p.s.refresh, 'function'); await p.s.refresh()
    assert.equal(name === 'StudentHomeView' ? p.s.exams.value.length : p.s.exam.value.id, name === 'StudentHomeView' ? 1 : 7)
    assert.equal(p.s.lastSync.value, previous); assert.match(p.s.error.value, /offline/); p.dispose()
  })
}
