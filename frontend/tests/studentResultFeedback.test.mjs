import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { parse, compileScript } from 'vue/compiler-sfc'
import * as vue from 'vue'

async function rendered(file, imports, props = {}) {
  const source = await readFile(new URL(`../src/${file}`, import.meta.url), 'utf8')
  const compiled = compileScript(parse(source).descriptor, { id: 'feedback', inlineTemplate: true })
  const code = compiled.content
    .replace(/import\s*{([^}]+)}\s*from\s*['"]([^'"]+)['"];?/g, (_, names, from) => `const {${names.replace(/\bas\b/g, ':')}} = imports[${JSON.stringify(from)}];`)
    .replace(/import\s+(\w+)\s+from\s*['"][^'"]+['"];?/g, 'const $1 = { render: () => null };')
    .replace('export default', 'const component =')
  const component = new Function('imports', `${code};return component`)({ vue, ...imports })
  const make = (tag, text = '') => ({ tag, text, children: [], props: {}, parent: null })
  const renderer = vue.createRenderer({
    createElement: tag => make(tag), createText: text => make('#text', text), createComment: text => make('#comment', text),
    patchProp: (node, key, oldValue, value) => { node.props[key] = value },
    insert: (node, parent, anchor) => { if (node.parent) { const old = node.parent.children.indexOf(node); if (old >= 0) node.parent.children.splice(old, 1) }; node.parent = parent; const at = anchor ? parent.children.indexOf(anchor) : -1; at >= 0 ? parent.children.splice(at, 0, node) : parent.children.push(node) },
    remove: node => { if (node.parent) node.parent.children.splice(node.parent.children.indexOf(node), 1) },
    setText: (node, text) => { node.text = text }, setElementText: (node, text) => { node.text = text; node.children = [] },
    parentNode: node => node.parent, nextSibling: node => node.parent?.children[node.parent.children.indexOf(node) + 1],
  })
  const root = make('root'), app = renderer.createApp(component, props)
  app.component('RouterLink', { setup: (_, { slots }) => () => vue.h('a', slots.default?.()) })
  app.mount(root)
  for (let i = 0; i < 10; i++) await vue.nextTick()
  const text = node => node.tag === '#comment' ? '' : node.text + node.children.map(text).join(' ')
  const value = text(root); app.unmount(); return value
}

const result = comment => ({ examTitle: '讲评验收', courseName: '课程', totalScore: 0, maxScore: 10, teacherComment: null, publishedAt: '2026-10-24T12:00:00', questions: [{ questionId: 1, questionType: 'SHORT_ANSWER', content: '题干', studentAnswer: '我的答案', finalScore: 0, maxScore: 10, options: [], teacherComment: comment }] })
async function student(comment) {
  return rendered('views/student/StudentResultDetailView.vue', { 'vue-router': { useRoute: () => ({ params: { submissionId: 7 } }) }, '../../api/student': { studentApi: { result: async () => ({ data: result(comment) }) }, formatTime: value => value } })
}

test('student sees long per-question feedback and zero scores without requiring an overall comment', async () => {
  const feedback = '请补充关键步骤。\n'.repeat(80)
  const text = await student(feedback)
  assert.match(text, /教师本题评语/); assert.ok(text.includes(feedback)); assert.match(text, /最终得分\s*0/)
  assert.doesNotMatch(text, /教师总评/)
})
test('empty feedback explains the absence without rendering null or undefined', async () => {
  const text = await student(null)
  assert.match(text, /教师暂未填写本题评语/); assert.doesNotMatch(text, /undefined|null/)
})
test('teacher results explain which feedback is visible after publication', async () => {
  const panel = await rendered('components/ExamGradingPanel.vue', { '../api/teacher': {}, '../utils/statusDisplay': {}, '../utils/examNavigation': {} }, { examId: 7, mode: 'results', workspace: { resultsReleasedAt: '2026-10-24T12:00:00', displayStage: 'COMPLETED' } })
  assert.match(panel, /本题评语/)
})
test('zero-participant analysis explains that no grading or release is needed', async () => {
  const data = { analysisReady: true, pendingGradingCount: 0, fullScore: 10, summary: { candidateCount: 2, participantCount: 0 }, distribution: [], students: [], questions: [] }
  const analysis = await rendered('components/ResultsAnalysisPanel.vue', { '../api/teacher': { teacherApi: { resultsAnalysis: async () => ({ data }) } } }, { examId: 7, displayStage: 'NO_PARTICIPANTS' })
  assert.match(analysis, /无人参加/); assert.doesNotMatch(analysis, /仍有.*未完成阅卷/)
})
