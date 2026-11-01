import test from 'node:test'
import assert from 'node:assert/strict'
import { setupView, renderView } from './helpers/viewHarness.mjs'
import { readFile } from 'node:fs/promises'
import * as examNavigation from '../src/utils/examNavigation.js'
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
