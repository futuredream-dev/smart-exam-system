import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { parse, compileScript } from 'vue/compiler-sfc'
import { computed, ref } from 'vue'
import { createBoundaryRefresh } from '../src/utils/examClock.js'

// Retained from the legacy verifier: entry and re-entry must reach the server before routing.
async function page(name, exam, rejected = false) {
  const source = await readFile(new URL(`../src/views/student/${name}.vue`, import.meta.url), 'utf8')
  const code = compileScript(parse(source).descriptor, { id: name, genDefaultAs: 'component' }).content.replace(/import\s*[\s\S]*?from\s*['"][^'"]+['"];?/g, '')
  const events = [], mounted = [], unmount = []
  const bindings = { computed, ref, onMounted: fn => mounted.push(fn), onBeforeUnmount: fn => unmount.push(fn),
    useRoute: () => ({ params: { examId: exam.id }, query: {} }), useRouter: () => ({ push: path => events.push(['navigate', path]) }),
    studentApi: { exam: async () => ({ data: exam }), exams: async () => ({ data: [exam] }), enter: async id => { events.push(['enter', id]); if (rejected) throw Error('答卷已提交，不能继续答题'); return { data: { submissionId: 42 } } } },
    formatTime: value => value, lifecycleMeta: () => ({}), sessionStorage: { getItem: () => null, removeItem() {} },
    document: { visibilityState: 'visible', addEventListener() {}, removeEventListener() {} },
    createBoundaryRefresh: options => createBoundaryRefresh({ ...options, schedule: () => 1, cancel() {} }) }
  const c = new Function(...Object.keys(bindings), `${code};return component`)(...Object.values(bindings))
  const s = c.setup({}, { expose() {} }); for (const fn of mounted) await fn()
  for (let i = 0; i < 12; i++) await Promise.resolve()
  return { s, events, dispose: () => unmount.forEach(fn => fn()) }
}
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
