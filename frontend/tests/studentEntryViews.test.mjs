import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { parse, compileScript } from 'vue/compiler-sfc'
import { computed, ref } from 'vue'
import { createBoundaryRefresh } from '../src/utils/examClock.js'
import { renderView } from './helpers/viewHarness.mjs'
const lifecycleSource = (await readFile(new URL('../src/utils/studentExamLifecycle.js', import.meta.url), 'utf8')).replace("'./statusDisplay'", JSON.stringify(new URL('../src/utils/statusDisplay.js', import.meta.url).href))
const { lifecycleMeta } = await import(`data:text/javascript;base64,${Buffer.from(lifecycleSource).toString('base64')}`)

// Retained from the legacy verifier: entry and re-entry must reach the server before routing.
async function page(name, exam, rejected = false) {
  const source = await readFile(new URL(`../src/views/student/${name}.vue`, import.meta.url), 'utf8')
  const code = compileScript(parse(source).descriptor, { id: name, genDefaultAs: 'component' }).content.replace(/import\s*[\s\S]*?from\s*['"][^'"]+['"];?/g, '')
  const events = [], mounted = [], unmount = []
  const bindings = { computed, ref, onMounted: fn => mounted.push(fn), onBeforeUnmount: fn => unmount.push(fn),
    useRoute: () => ({ params: { examId: exam.id }, query: {} }), useRouter: () => ({ push: path => events.push(['navigate', path]) }),
    studentApi: { exam: async () => ({ data: exam }), exams: async () => ({ data: [exam] }), enter: async id => { events.push(['enter', id]); if (rejected) throw Error('答卷已提交，不能继续答题'); return { data: { submissionId: 42 } } } },
    formatTime: value => value, lifecycleMeta, sessionStorage: { getItem: () => null, removeItem() {} },
    document: { visibilityState: 'visible', addEventListener() {}, removeEventListener() {} },
    createBoundaryRefresh: options => createBoundaryRefresh({ ...options, schedule: () => 1, cancel() {} }) }
  const c = new Function(...Object.keys(bindings), `${code};return component`)(...Object.values(bindings))
  const s = c.setup({}, { expose() {} }); for (const fn of mounted) await fn()
  for (let i = 0; i < 12; i++) await Promise.resolve()
  return { s, events, dispose: () => unmount.forEach(fn => fn()) }
}

for (const name of ['StudentHomeView', 'StudentExamDetailView']) test(`${name} renders actual lifecycle actions and a safe unknown-state fallback`, async () => {
  const cases = [
    ['NOT_STARTED', name === 'StudentHomeView' ? '查看详情' : null],
    ['AVAILABLE', '进入考试'], ['IN_PROGRESS', '继续考试'],
    ['MISSED_ENTRY', null], ['PENDING_REVIEW', null], ['PENDING_RELEASE', null],
    ['RESULTS_RELEASED', '查看成绩'], ['ABSENT', null], ['FUTURE_UNKNOWN', null],
  ]
  for (const [status, action] of cases) {
    const exam = { id: 7, submissionId: 99, title: '映射验收', courseName: '课程', lifecycleStatus: status, serverNow: '2026-11-02T00:00:00Z' }
    const p = await page(name, exam)
    try {
      const html = await renderView(`views/student/${name}.vue`, p.s)
      const actions = [...html.matchAll(/>(进入考试|继续考试|查看详情|查看成绩)<\/(?:button|a)>/g)].map(m => m[1])
      assert.deepEqual(actions, action ? [action] : [], `${name}/${status}`)
      if (status === 'FUTURE_UNKNOWN') { assert.match(html, /状态待确认/); assert.match(html, /请稍后刷新查看/) }
      if (name === 'StudentHomeView') {
        await p.s.act(exam)
        const expected = ['AVAILABLE', 'IN_PROGRESS'].includes(status) ? [['enter', 7], ['navigate', '/student/paper/42']]
          : status === 'NOT_STARTED' ? [['navigate', '/student/exams/7']]
          : status === 'RESULTS_RELEASED' ? [['navigate', '/student/results/99']] : []
        assert.deepEqual(p.events, expected)
      }
    } finally { p.dispose() }
  }
})
for (const name of ['StudentHomeView', 'StudentExamDetailView']) for (const status of ['AVAILABLE', 'IN_PROGRESS']) {
  test(`${name} ${status} confirms entry server-side and never routes after rejection`, async () => {
    const exam = { id: 7, submissionId: 99, lifecycleStatus: status }
    for (const rejected of [false, true]) {
      const p = await page(name, exam, rejected)
      try {
        await (p.s.act ? p.s.act(exam) : p.s.enter())
        assert.deepEqual(p.events, rejected ? [['enter', 7]] : [['enter', 7], ['navigate', '/student/paper/42']])
        if (rejected) assert.equal(p.s.error.value, '答卷已提交，不能继续答题')
      } finally { p.dispose() }
    }
  })
}
