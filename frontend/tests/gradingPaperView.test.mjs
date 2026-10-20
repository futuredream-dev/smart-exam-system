import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { parse, compileScript } from 'vue/compiler-sfc'
import { computed, reactive, ref } from 'vue'
import { mergeReviewDraft } from '../src/utils/gradingPaperState.js'

async function page(overrides = {}) {
  const source = await readFile(new URL('../src/views/teacher/GradingPaperView.vue', import.meta.url), 'utf8')
  const compiled = compileScript(parse(source).descriptor, { id: 'grading', genDefaultAs: 'component' })
  const code = compiled.content.replace(/import\s*[\s\S]*?from\s*['"][^'"]+['"];?/g, '')
  const server = { id: 10, examId: 7, teacherComment: '', totalScore: 0, scorePublished: false, answers: [1, 2].map(answerId => ({
    answerId, questionType: 'SHORT_ANSWER', teacherScore: null, autoScore: null, teacherComment: '', gradingStatus: 'PENDING', maxScore: 10,
  })) }
  const calls = [], navigations = []
  const api = {
    reviewAnswer: async (id, body) => {
      calls.push(['answer', id, body.teacherScore, body.teacherComment])
      if (overrides.failOn === id) throw new Error('保存失败')
      Object.assign(server.answers.find(a => a.answerId === id), { teacherScore: body.teacherScore, teacherComment: body.teacherComment, gradingStatus: 'REVIEWED' })
      return { data: structuredClone(server) }
    },
    saveFinalComment: async (id, value) => { calls.push(['comment', id, value]); server.teacherComment = value; return { data: structuredClone(server) } },
    gradingSubmissions: async () => ({ data: [{ id: 10, pendingManualReview: false }, { id: 11, pendingManualReview: true }] }),
    ...overrides.api,
  }
  const component = new Function('computed', 'onMounted', 'reactive', 'ref', 'useRoute', 'useRouter', 'teacherApi', 'mergeReviewDraft', 'sessionStorage', `${code}; return component`)(
    computed, () => {}, reactive, ref, () => ({ params: {}, query: {} }), () => ({ push: async path => navigations.push(path) }),
    api, mergeReviewDraft, { setItem() {} },
  )
  const state = component.setup({}, { expose() {} }); state.hydrate(structuredClone(server))
  return { state, server, calls, navigations }
}
test('save and next saves every dirty answer and final comment before navigating', async () => {
  const { state: s, calls, navigations } = await page()
  s.scores[1] = 6; s.scores[2] = 8; s.comments[2] = '保留'; s.finalComment.value = '总评'
  await s.saveAndNext()
  assert.deepEqual(calls, [['answer', 1, 6, ''], ['answer', 2, 8, '保留'], ['comment', 10, '总评']])
  assert.deepEqual(navigations, ['/teacher/exams/7/grading/submissions/11'])
})
test('partial save failure stays on the paper and retry writes only remaining dirty fields', async () => {
  const config = { failOn: 2 }, { state: s, calls, navigations } = await page(config)
  s.scores[1] = 6; s.scores[2] = 8; s.comments[2] = '仍在'; s.finalComment.value = '未丢'
  await s.saveAndNext()
  assert.equal(navigations.length, 0); assert.match(s.error.value, /保存失败/)
  assert.equal(s.scores[2], 8); assert.equal(s.comments[2], '仍在'); assert.equal(s.finalComment.value, '未丢')
  assert.equal(s.base.scores[1], 6); assert.equal(s.base.scores[2], 0)
  config.failOn = null
  // Reuse the same real handlers and persisted server response after changing only the failing boundary.
  // page reads the live options object for failOn.
  await s.saveAndNext()
  assert.deepEqual(calls.map(c => c.slice(0, 2)), [['answer', 1], ['answer', 2], ['answer', 2], ['comment', 10]])
  assert.equal(navigations.length, 1)
})
test('pending zero-score answers require explicit review before next paper', async () => {
  const { state: s, calls, navigations } = await page()
  await s.saveAndNext()
  assert.equal(calls.length, 0); assert.equal(navigations.length, 0); assert.match(s.error.value, /未完成人工评分/)
})
test('a delayed answer save preserves newer input and blocks duplicate writes', async () => {
  let resolve
  const { state: s, server, calls } = await page({ api: { reviewAnswer: () => new Promise(done => { resolve = done }) } })
  s.scores[1] = 6
  const pending = s.review(s.paper.value.answers[0])
  s.scores[1] = 9; s.comments[1] = '请求后编辑'
  await s.saveAndNext()
  assert.equal(calls.length, 0)
  server.answers[0].teacherScore = 6; server.answers[0].gradingStatus = 'REVIEWED'
  resolve({ data: structuredClone(server) }); await pending
  assert.equal(s.scores[1], 9); assert.equal(s.base.scores[1], 6); assert.equal(s.comments[1], '请求后编辑')
})
test('an uncertain AI timeout refreshes once without repeating the AI call or losing drafts', async () => {
  let aiCalls = 0, reads = 0
  const { state: s } = await page({ api: {
    aiGrade: async () => { aiCalls++; throw { errorCode: 'REQUEST_TIMEOUT', message: '请求超时' } },
    gradingPaper: async () => { reads++; return { data: { id: 10, examId: 7, teacherComment: '', answers: [1, 2].map(answerId => ({ answerId, questionType: 'SHORT_ANSWER', teacherScore: null, autoScore: null, teacherComment: '', gradingStatus: 'PENDING' })) } } },
  } })
  s.scores[2] = 8; s.comments[2] = '保留草稿'
  await s.aiGrade(s.paper.value.answers[0])
  assert.equal(aiCalls, 1); assert.equal(reads, 1); assert.equal(s.scores[2], 8); assert.equal(s.comments[2], '保留草稿')
  assert.match(s.notice.value, /刷新|确认/)
})
test('a server RUNNING suggestion blocks regeneration and provides a read-only status refresh', async () => {
  let aiCalls = 0, reads = 0
  const { state: s, server } = await page({ api: {
    aiGrade: async () => { aiCalls++; return { data: structuredClone(server) } },
    gradingPaper: async () => { reads++; return { data: structuredClone(server) } },
  } })
  server.answers[0].aiSuggestion = { status: 'RUNNING' }; s.hydrate(structuredClone(server))
  s.scores[2] = 8; s.comments[2] = '刷新保留草稿'
  await s.aiGrade(s.paper.value.answers[0]); assert.equal(aiCalls, 0)
  assert.equal(typeof s.refreshAiResult, 'function'); await s.refreshAiResult()
  assert.equal(reads, 1); assert.equal(aiCalls, 0); assert.equal(s.scores[2], 8); assert.equal(s.comments[2], '刷新保留草稿')
})
