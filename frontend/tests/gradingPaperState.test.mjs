import test from 'node:test'
import assert from 'node:assert/strict'
import { resolveFinalComment } from '../src/utils/gradingPaperState.js'

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
