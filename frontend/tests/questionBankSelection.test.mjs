import test from 'node:test'
import assert from 'node:assert/strict'
const module = await import('../src/utils/questionBankSelection.js').catch(() => ({}))
const call = (name, ...args) => { assert.equal(typeof module[name], 'function', `${name} is not implemented`); return module[name](...args) }
test('page selection preserves hidden choices and deselects only the current page', () => {
  const rows = [{ id: 3, status: 'DRAFT' }, { id: 4, status: 'READY' }, { id: 5, status: 'ARCHIVED' }]
  assert.deepEqual(call('updateQuestionSelection', [1, 2], rows, true), { ids: [1, 2, 3, 4], error: '' })
  assert.deepEqual(call('updateQuestionSelection', [1, 2, 3, 4], rows, false), { ids: [1, 2], error: '' })
})
test('selection limit rejects the whole addition while allowing removal at the limit', () => {
  const selected = Array.from({ length: 200 }, (_, i) => i + 1)
  const result = call('updateQuestionSelection', selected, [{ id: 201, status: 'READY' }], true)
  assert.deepEqual(result.ids, selected); assert.match(result.error, /200/)
  assert.equal(call('updateQuestionSelection', selected, [{ id: 200, status: 'READY' }], false).ids.length, 199)
})
test('only known archived visible choices are removed and reported', () => {
  assert.deepEqual(call('reconcileQuestionSelection', [1, 2, 3], [{ id: 2, status: 'ARCHIVED' }, { id: 4, status: 'READY' }]), { ids: [1, 3], removed: [2] })
})
test('URL filters retain repeated or comma-separated metadata and explicit archived choice', () => {
  const parsed = call('parseBankPageQuery', { keyword: 'Java', chapterIds: ['3', '4'], knowledgePointIds: '8,9', tagIds: ['2','2'], status: 'ARCHIVED', page: '3', size: '50' })
  assert.equal(parsed.page, 3); assert.equal(parsed.size, 50)
  assert.deepEqual(parsed.chapterIds, [3, 4]); assert.deepEqual(parsed.knowledgePointIds, [8, 9]); assert.deepEqual(parsed.tagIds, [2])
  assert.equal(call('bankPageParams', parsed).excludeArchived, false)
  const defaults = call('parseBankPageQuery', {})
  assert.equal(defaults.size, 20); assert.equal(defaults.page, 1); assert.equal(call('bankPageParams', defaults).excludeArchived, true)
  assert.equal(call('parseBankPageQuery', { size: '500', page: '-2' }).size, 20)
})
