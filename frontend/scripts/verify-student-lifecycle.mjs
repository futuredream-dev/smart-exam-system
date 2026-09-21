import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { parse, compileScript } from 'vue/compiler-sfc'
import { ref } from 'vue'
import { lifecycleMeta } from '../src/utils/studentExamLifecycle.js'

const expected = {
  NOT_STARTED: ['待开始', '查看详情'],
  AVAILABLE: ['可参加考试', '进入考试'],
  MISSED_ENTRY: ['已错过入场', null],
  IN_PROGRESS: ['答题中', '继续答题'],
  PENDING_REVIEW: ['已交卷 · 待批阅', null],
  PENDING_RELEASE: ['已交卷 · 待公布', null],
  RESULTS_RELEASED: ['成绩已公布', '查看成绩'],
  ABSENT: ['已结束 · 未参加', null],
}

for (const [status, [label, action]] of Object.entries(expected)) {
  assert.equal(lifecycleMeta(status).label, label)
  assert.equal(lifecycleMeta(status).action, action)
}
assert.equal(lifecycleMeta('INTERNAL_UNKNOWN').label, '状态待确认')
console.log('PASS student lifecycle labels and actions')

// Execute the real page handlers with only their router/API boundaries replaced.
// A resume action must call the server before navigation, otherwise last_enter_time
// is never updated and stale/closed submissions can be opened optimistically.
async function pageHandlers(name, exam, rejectEntry = false) {
  const source = await readFile(new URL(`../src/views/student/${name}.vue`, import.meta.url), 'utf8')
  const { descriptor } = parse(source)
  const compiled = compileScript(descriptor, { id: name, genDefaultAs: 'component' })
  const code = compiled.content.replace(/import\s*[\s\S]*?from\s*['"][^'"]+['"];?/g, '')
  const events = [], mounted = []
  const api = {
    exam: async () => ({ data: exam }),
    exams: async () => ({ data: [exam] }),
    enter: async id => {
      events.push(['enter', id])
      if (rejectEntry) throw new Error('答卷已提交，不能继续答题')
      return { data: { submissionId: 42 } }
    },
  }
  const component = new Function('ref', 'onMounted', 'useRoute', 'useRouter', 'studentApi',
    'formatTime', 'lifecycleMeta', 'sessionStorage', `${code}; return component`)(
    ref, callback => mounted.push(callback), () => ({ params: { examId: exam.id } }),
    () => ({ push: path => events.push(['navigate', path]) }), api, value => value,
    lifecycleMeta, { getItem: () => null },
  )
  const bindings = component.setup({}, { expose() {} })
  for (const callback of mounted) await callback()
  return { bindings, events }
}

for (const name of ['StudentHomeView', 'StudentExamDetailView']) {
  for (const status of ['AVAILABLE', 'IN_PROGRESS']) {
    const exam = { id: 7, submissionId: 99, lifecycleStatus: status }
    const { bindings, events } = await pageHandlers(name, exam)
    await (bindings.act ? bindings.act(exam) : bindings.enter())
    assert.deepEqual(events, [['enter', 7], ['navigate', '/student/paper/42']], `${name} ${status}`)
    const rejected = await pageHandlers(name, exam, true)
    await (rejected.bindings.act ? rejected.bindings.act(exam) : rejected.bindings.enter())
    assert.deepEqual(rejected.events, [['enter', 7]], `${name} must not navigate after server rejection`)
    assert.equal(rejected.bindings.error.value, '答卷已提交，不能继续答题')
  }
}
console.log('PASS student entry/re-entry server authority and rejected-entry navigation')
