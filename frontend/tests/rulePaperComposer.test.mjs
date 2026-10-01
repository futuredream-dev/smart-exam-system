import test from 'node:test'
import assert from 'node:assert/strict'
import { createRule, changeRuleBank, invalidateResults, summarizeRules, validateRules, rulesPayload, confirmPayload } from '../src/utils/rulePaperComposer.js'

test('rules add and delete without changing remaining order', () => {
  const rules = [createRule(), createRule(), createRule()]
  rules[0].bankId = 1; rules[1].bankId = 2; rules[2].bankId = 3
  rules.splice(1, 1)
  assert.deepEqual(rules.map(rule => rule.bankId), [1, 3])
  assert.deepEqual(createRule(), { bankId: '', chapterIds: [], knowledgePointIds: [], tagIds: [], questionType: 'SINGLE_CHOICE', easyCount: 0, mediumCount: 0, hardCount: 0, scorePerQuestion: 2 })
})

test('changing bank clears all metadata IDs', () => {
  const rule = { ...createRule(), bankId: 1, chapterIds: [2], knowledgePointIds: [3], tagIds: [4] }
  changeRuleBank(rule, '5')
  assert.equal(rule.bankId, '5')
  assert.deepEqual([rule.chapterIds, rule.knowledgePointIds, rule.tagIds], [[], [], []])
})

test('rule edits invalidate diagnosis and preview', () => {
  const state = { stage: 'PREVIEW', diagnoseResult: { feasible: true }, previewResult: { selectedQuestions: [1] } }
  invalidateResults(state)
  assert.deepEqual(state, { stage: 'CONFIG', diagnoseResult: null, previewResult: null })
})

test('summary and simple rule validation use cents and whole counts', () => {
  const rule = { ...createRule(), bankId: '7', easyCount: 2, mediumCount: 1, scorePerQuestion: 2.25 }
  assert.deepEqual(summarizeRules([rule]), { ruleCount: 1, totalRequested: 3, estimatedTotalScore: '6.75' })
  assert.equal(validateRules([rule]), '')
  assert.match(validateRules([{ ...rule, easyCount: 1.5 }]), /整数/)
  assert.match(validateRules([{ ...rule, easyCount: '' }]), /整数/)
  assert.match(validateRules([{ ...rule, easyCount: 0, mediumCount: 0 }]), /至少/)
  assert.match(validateRules([{ ...rule, scorePerQuestion: 0.001 }]), /两位小数/)
  assert.match(validateRules([{ ...rule, easyCount: 101 }]), /100/)
})

test('diagnose and preview share rules; confirm uses current preview selections in order', () => {
  const rule = { ...createRule(), bankId: '7', chapterIds: [2], knowledgePointIds: [3], tagIds: [4], hardCount: 1, scorePerQuestion: 2.5 }
  const request = rulesPayload([rule])
  assert.deepEqual(request, { rules: [{ bankId: 7, chapterIds: [2], knowledgePointIds: [3], tagIds: [4], questionType: 'SINGLE_CHOICE', easyCount: 0, mediumCount: 0, hardCount: 1, scorePerQuestion: 2.5 }] })
  const currentPreview = { selectedQuestions: [{ ruleIndex: 0, difficulty: 'HARD', bankItemId: 19, score: 2.5, title: '题目' }] }
  assert.deepEqual(confirmPayload([rule], currentPreview), { ...request, selections: [{ ruleIndex: 0, difficulty: 'HARD', bankItemId: 19, score: 2.5 }] })
})

test('regenerated preview replaces the IDs sent to confirm', () => {
  const rule = { ...createRule(), bankId: '7', easyCount: 1 }
  const state = { stage: 'PREVIEW', diagnoseResult: { feasible: true },
    previewResult: { selectedQuestions: [{ ruleIndex: 0, difficulty: 'EASY', bankItemId: 101, score: 2 }] } }
  const first = confirmPayload([rule], state.previewResult)
  state.previewResult = { selectedQuestions: [{ ruleIndex: 0, difficulty: 'EASY', bankItemId: 102, score: 2 }] }
  const latest = confirmPayload([rule], state.previewResult)
  assert.equal(first.selections[0].bankItemId, 101)
  assert.equal(latest.selections[0].bankItemId, 102)
  invalidateResults(state)
  assert.equal(state.previewResult, null)
})
