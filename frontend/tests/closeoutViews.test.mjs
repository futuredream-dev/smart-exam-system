import test from 'node:test'
import assert from 'node:assert/strict'
import { setupView, renderView, settle } from './helpers/viewHarness.mjs'
import { reactive } from 'vue'
import { readFile } from 'node:fs/promises'
import * as examNavigation from '../src/utils/examNavigation.js'
import * as aiComposer from '../src/utils/aiPaperComposer.js'
const centerSource = (await readFile(new URL('../src/utils/examCenter.js', import.meta.url), 'utf8')).replace("'./statusDisplay'", JSON.stringify(new URL('../src/utils/statusDisplay.js', import.meta.url).href))
const examCenter = await import(`data:text/javascript;base64,${Buffer.from(centerSource).toString('base64')}`)

test('a fully reviewed running exam explains that publication waits for the end', async () => {
  const exam = { id: 7, title: '尚未截止', status: 'PUBLISHED', displayStage: 'RUNNING', nextAction: 'VIEW_MONITOR', courseName: '课程', startTime: '2026-11-01T10:00:00', endTime: '2026-11-01T11:00:00', questionTypeCounts: {}, submittedCount: 1, gradedCount: 1, pendingGradingCount: 0 }
  const route = { params: { examId: 7 }, query: { tab: 'grading' }, path: '/teacher/exams/7' }
  const page = await setupView('views/teacher/ExamDetailView.vue', {
    'vue-router': { useRoute: () => route, useRouter: () => ({}) },
    '../../api/teacher': { teacherApi: { exam: async () => ({ data: exam }), examWorkspace: async () => ({ data: {} }), questions: async () => ({ data: [] }) }, formatTime: value => value },
    '../../utils/examCenter': examCenter, '../../utils/examNavigation': examNavigation,
  })
  await page.mount()
  const html = await renderView('views/teacher/ExamDetailView.vue', page.state)
  assert.match(html, /考试结束后/); assert.doesNotMatch(html, /仍有答卷尚未完成阅卷/); page.dispose()
})

test('pending final statistics do not invent unreviewed papers when their count is zero', async () => {
  const data = { analysisReady: false, pendingGradingCount: 0, fullScore: 25, summary: { candidateCount: 2, participantCount: 1 }, distribution: [], students: [], questions: [] }
  const page = await setupView('components/ResultsAnalysisPanel.vue', { '../api/teacher': { teacherApi: { resultsAnalysis: async () => ({ data }) } } }, { examId: 7, refreshKey: 0, displayStage: 'RUNNING' })
  await page.mount()
  const html = await renderView('components/ResultsAnalysisPanel.vue', page.state)
  assert.match(html, /考试尚未结束/); assert.doesNotMatch(html, /仍有 0 份答卷未完成阅卷/); page.dispose()
})

for (const [stage, ready, participants, nobody] of [
  ['NO_PARTICIPANTS', false, 1, true], ['COMPLETED', true, 0, true],
  ['RUNNING', false, 0, false], ['COMPLETED', true, 1, false],
]) test(`analysis handles independent absence predicates: ${stage}/${ready}/${participants}`, async () => {
  const data = { analysisReady: ready, pendingGradingCount: 0, fullScore: 10,
    summary: { candidateCount: 2, participantCount: participants, averageScore: ready ? 0 : null },
    distribution: [{ label: '0–59', count: 0 }, { label: '60–79', count: 0 }],
    students: [{ studentId: 1, studentName: '缺考示例', username: 'synthetic', candidateStatus: 'ABSENT', totalScore: null }], questions: [] }
  const page = await setupView('components/ResultsAnalysisPanel.vue', { '../api/teacher': { teacherApi: { resultsAnalysis: async () => ({ data }) } } }, { examId: 7, displayStage: stage })
  await page.mount()
  const html = await renderView('components/ResultsAnalysisPanel.vue', page.state)
  assert.equal((html.match(/无人参加/g) || []).length, nobody ? 1 : 0)
  assert.equal(html.includes('results-bucket-track'), !nobody && ready)
  assert.match(html, /缺考示例/)
  if (nobody) assert.doesNotMatch(html, /仍有.*未完成阅卷|待完成阅卷/)
})

test('AI failure preserves the requirement and does not duplicate the backend fallback guidance', async () => {
  const page = await setupView('components/AiPaperComposer.vue', {
    '../api/teacher': { teacherApi: { interpretAiAssembly: async () => { throw Error('AI 未配置，可切换到规则智能组卷继续。') } } },
    '../utils/aiPaperComposer': aiComposer,
  }, { examId: 7, initialRequirement: '集合与异常处理' })
  await page.state.interpret()
  const html = await renderView('components/AiPaperComposer.vue', page.state)
  const alert = html.match(/role="alert">(.*?)<\/p>/s)[1]
  assert.equal((alert.match(/规则智能组卷/g) || []).length, 1)
  assert.equal(page.state.requirement.value, '集合与异常处理')
  assert.match(html, /切换到规则智能组卷<\/button>/)
})

for (const selection of ['', 'not-authorized']) test(`clearing the analysis selection (${selection || 'empty'}) ends an in-flight loading state`, async () => {
  let finish
  const pending = new Promise(resolve => { finish = resolve })
  const route = reactive({ query: { examId: '7' } })
  const page = await setupView('views/teacher/AiEvaluationView.vue', {
    'vue-router': { useRoute: () => route, useRouter: () => ({ replace() {} }) },
    '../../api/teacher': { teacherApi: { aiEvaluationSummary: () => pending, aiEvaluationQuestions: () => pending }, loadWorkspace: async () => ({ exams: [{ id: 7, title: '可访问' }] }) },
    '../../utils/examNavigation': examNavigation,
  })
  await page.mount()
  if (selection) route.query.examId = selection
  else page.state.examId.value = ''
  await settle()
  assert.equal(page.state.loading.value, false)
  finish({ data: { sampleCount: 1 } })
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(page.state.summary.value, null)
  assert.doesNotMatch(await renderView('views/teacher/AiEvaluationView.vue', page.state), /正在加载分析数据/)
})
