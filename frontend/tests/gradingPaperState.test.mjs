import test from 'node:test'
import assert from 'node:assert/strict'
import { resolveFinalComment } from '../src/utils/gradingPaperState.js'
import * as gradingState from '../src/utils/gradingPaperState.js'

// Losing an unrelated dirty answer on a full-paper response is a data-loss bug.
const serverPaper = (overrides = {}) => ({ id: 10, teacherComment: '', answers: [
  { answerId: 1, questionType: 'SHORT_ANSWER', teacherScore: null, autoScore: null, teacherComment: '', gradingStatus: 'PENDING' },
  { answerId: 2, questionType: 'SHORT_ANSWER', teacherScore: null, autoScore: null, teacherComment: '', gradingStatus: 'PENDING' },
], ...overrides })
function merge(state, paper, saved = {}) {
  assert.equal(typeof gradingState.mergeReviewDraft, 'function', 'draft merge is not implemented')
  return gradingState.mergeReviewDraft(state, paper, saved)
}
function draft() {
  return { paperId: 10, scores: { 1: 6, 2: 8 }, comments: { 1: '第一题', 2: '第二题草稿' }, finalComment: '未保存总评',
    baseline: { scores: { 1: 0, 2: 0 }, comments: { 1: '', 2: '' }, finalComment: '' } }
}
for (const operation of ['answer', 'AI', 'finalComment']) {
  test(`${operation} refresh retains another answer score, comment and unsaved final comment`, () => {
    const state = draft(), paper = serverPaper()
    const saved = operation === 'answer' ? { answerIds: [1] } : operation === 'finalComment' ? { finalComment: true } : {}
    paper.answers[0].teacherScore = 6; paper.answers[0].teacherComment = '第一题'
    if (saved.finalComment) paper.teacherComment = '未保存总评'
    const result = merge(state, paper, saved)
    assert.equal(result.scores[2], 8); assert.equal(result.comments[2], '第二题草稿')
    assert.equal(result.finalComment, '未保存总评'); assert.equal(result.baseline.scores[2], 0)
    assert.deepEqual(state, draft(), 'merge must not mutate the input draft')
  })
}
test('a successful save advances the baseline without overwriting a newer edit during the request', () => {
  const state = draft(), sent = structuredClone(state), paper = serverPaper()
  state.scores[1] = 9; state.comments[1] = '发送后继续编辑'; state.finalComment = '新的总评'
  paper.answers[0].teacherScore = 6; paper.answers[0].teacherComment = '第一题'; paper.teacherComment = '未保存总评'
  const result = merge(state, paper, { answerIds: [1], finalComment: true, submitted: sent })
  assert.equal(result.scores[1], 9); assert.equal(result.comments[1], '发送后继续编辑')
  assert.equal(result.finalComment, '新的总评'); assert.equal(result.baseline.scores[1], 6)
  assert.equal(result.baseline.comments[1], '第一题'); assert.equal(result.baseline.finalComment, '未保存总评')
})
test('initial pending zero remains clean and is not marked manually reviewed', () => {
  const paper = serverPaper(), result = merge({}, paper)
  assert.equal(result.scores[1], 0); assert.equal(result.baseline.scores[1], 0)
  assert.equal(paper.answers[0].gradingStatus, 'PENDING')
})
test('switching papers discards old drafts even when answer IDs collide', () => {
  const result = merge(draft(), serverPaper({ id: 11, teacherComment: '新答卷' }))
  assert.equal(result.paperId, 11); assert.equal(result.scores[2], 0)
  assert.equal(result.comments[2], ''); assert.equal(result.finalComment, '新答卷')
})
test('clean fields follow server normalization while unrelated fields stay dirty', () => {
  const state = draft(); state.scores[1] = 0; state.comments[1] = ''
  const paper = serverPaper(); paper.answers[0].teacherScore = 4; paper.answers[0].teacherComment = '服务端值'
  const result = merge(state, paper)
  assert.equal(result.scores[1], 4); assert.equal(result.comments[1], '服务端值'); assert.equal(result.scores[2], 8)
})

test('review and AI refresh keep unsaved final comment while baseline remains server value', () => {
  const refreshed = resolveFinalComment('教师尚未保存的总评', '', true)
  assert.equal(refreshed.text, '教师尚未保存的总评')
  assert.equal(refreshed.saved, '')
  assert.notEqual(refreshed.text, refreshed.saved)
})

test('first load and switching paper show the saved server comment', () => {
  const loaded = resolveFinalComment('上一份答卷的草稿', '这份答卷已保存的总评', false)
  assert.equal(loaded.text, '这份答卷已保存的总评')
  assert.equal(loaded.saved, '这份答卷已保存的总评')
})

test('saved response updates the baseline so save-and-next has no redundant comment write', () => {
  const saved = resolveFinalComment('待保存', '服务端已保存', false)
  assert.equal(saved.text, saved.saved)
})
