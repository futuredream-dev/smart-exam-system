import test from 'node:test'
import assert from 'node:assert/strict'
import { reactive } from 'vue'
import { createAiRule, draftFromMappings, createRequestGate, copyRules } from '../src/utils/aiPaperComposer.js'

test('AI draft never invents counts or scores', () => {
  const rule = createAiRule()
  assert.equal(rule.easyCount, '')
  assert.equal(rule.mediumCount, '')
  assert.equal(rule.hardCount, '')
  assert.equal(rule.scorePerQuestion, '')
})

test('explicit two medium single choice questions remain exactly two without metadata mapping', () => {
  const intent = { questionCountHint: 2, difficultyIntent: { overall: 'MEDIUM' },
    questionTypeIntent: [{ questionType: 'SINGLE_CHOICE', countHint: 2 }],
    topicKeywords: ['Java 集合', '异常处理'] }
  const mappings = [{ text: 'Java 集合', status: 'UNRESOLVED', options: [] },
    { text: '异常处理', status: 'UNRESOLVED', options: [] }]
  const draft = draftFromMappings(mappings, ['', ''], intent)
  assert.equal(draft.length, 1)
  assert.equal(draft[0].questionType, 'SINGLE_CHOICE')
  assert.deepEqual([draft[0].easyCount, draft[0].mediumCount, draft[0].hardCount], [0, 2, 0])
  assert.equal(draft[0].bankId, '')
  assert.equal(draft[0].scorePerQuestion, '')
})

test('multiple bank mappings do not duplicate the explicit total question count', () => {
  const intent = { questionCountHint: 2, difficultyIntent: { overall: 'MEDIUM' },
    questionTypeIntent: [{ questionType: 'SINGLE_CHOICE', countHint: 2 }] }
  const mappings = [{ options: [{ bankId: 4, dimension: 'TAG', id: 8 }] },
    { options: [{ bankId: 5, dimension: 'TAG', id: 9 }] }]
  const draft = draftFromMappings(mappings, ['4:TAG:8', '5:TAG:9'], intent)
  assert.equal(draft.length, 2)
  assert.equal(draft.reduce((sum, rule) => sum + Number(rule.easyCount || 0) + Number(rule.mediumCount || 0) + Number(rule.hardCount || 0), 0), 0)
})

test('teacher-selected metadata forms editable rule draft using only returned IDs', () => {
  const mappings = [{ options: [{ bankId: 4, dimension: 'KNOWLEDGE_POINT', id: 31, name: '集合' }] },
    { options: [{ bankId: 4, dimension: 'TAG', id: 42, name: '应用' }] }]
  const draft = draftFromMappings(mappings, ['4:KNOWLEDGE_POINT:31', '4:TAG:42'])
  assert.equal(draft.length, 1)
  assert.deepEqual(draft[0].knowledgePointIds, [31])
  assert.deepEqual(draft[0].tagIds, [42])
  assert.equal(draft[0].bankId, 4)
  assert.equal(draft[0].scorePerQuestion, '')
})

test('stale async response cannot replace newer rules', () => {
  const gate = createRequestGate()
  const old = gate.capture()
  gate.invalidate()
  assert.equal(gate.current(old), false)
  assert.equal(gate.current(gate.capture()), true)
})

test('rule draft can be copied from Vue reactive state without sharing metadata arrays', () => {
  const source = reactive([{ ...createAiRule(), bankId: 4, knowledgePointIds: [31] }])
  const copied = copyRules(source)
  assert.deepEqual(copied, [{ bankId: 4, chapterIds: [], knowledgePointIds: [31], tagIds: [],
    questionType: '', easyCount: '', mediumCount: '', hardCount: '', scorePerQuestion: '' }])
  source[0].knowledgePointIds.push(32)
  assert.deepEqual(copied[0].knowledgePointIds, [31])
})
