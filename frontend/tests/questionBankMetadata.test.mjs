import test from 'node:test'
import assert from 'node:assert/strict'
import { activeChapterOptions, bankFilterParams, banksForExam, batchMetadataPayload, chapterPath, compactNames } from '../src/utils/questionBankMetadata.js'

test('course choices respect one bank per course and exam ownership', () => {
  const banks = [{ id: 11, courseId: 1, scope: 'TEACHER' }, { id: 12, courseId: null, scope: 'TEACHER' }, { id: 13, courseId: null, scope: 'PLATFORM' }]
  assert.deepEqual(banksForExam(banks, 1).map(row => row.id), [11, 13])
  assert.deepEqual(banksForExam(banks, 2).map(row => row.id), [13])
})

test('metadata labels preserve old and archived item states', () => {
  const chapters = [{ id: 1, parentId: null, name: '集合框架', status: 'ACTIVE' }, { id: 2, parentId: 1, name: 'Map', status: 'ACTIVE' }]
  assert.equal(chapterPath(null, chapters), '未分类')
  assert.equal(chapterPath(2, chapters), '集合框架 / Map')
  assert.equal(chapterPath(99, chapters), '已归档章节')
  assert.equal(chapterPath(3, [...chapters, { id: 3, parentId: null, name: '泛型', status: 'ARCHIVED' }]), '泛型（已归档）')
  assert.deepEqual(activeChapterOptions(chapters).map(row => row.label), ['集合框架', '集合框架 / Map'])
  assert.equal(compactNames([1, 2, 3], [{ id: 1, name: 'HashMap' }, { id: 2, name: '接口' }, { id: 3, name: '集合' }]), 'HashMap · 接口 · +1')
  assert.equal(compactNames([4], [{ id: 4, name: '线程安全', status: 'ARCHIVED' }]), '线程安全（已归档）')
})

test('filters and batch requests keep backend AND/OR and explicit IDs', () => {
  assert.deepEqual(bankFilterParams({ keyword: '', chapterIds: [1, 2], knowledgePointIds: [3, 4], difficulty: 'MEDIUM', tagIds: [] }),
    { chapterIds: [1, 2], knowledgePointIds: [3, 4], difficulty: 'MEDIUM' })
  assert.deepEqual(batchMetadataPayload([11, 12], 'SET_CHAPTER', '7'), { itemIds: [11, 12], chapterMode: 'SET', chapterId: 7 })
  assert.deepEqual(batchMetadataPayload([11], 'ADD_KNOWLEDGE', ['3', '4']), { itemIds: [11], knowledgePointMode: 'ADD', knowledgePointIds: [3, 4] })
  assert.deepEqual(batchMetadataPayload([11], 'REMOVE_TAG', ['8']), { itemIds: [11], tagMode: 'REMOVE', tagIds: [8] })
  assert.deepEqual(batchMetadataPayload([11], 'CLEAR_DIFFICULTY'), { itemIds: [11], difficultyMode: 'CLEAR' })
})
