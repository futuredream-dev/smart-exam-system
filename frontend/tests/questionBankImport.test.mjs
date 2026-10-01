import test from 'node:test'
import assert from 'node:assert/strict'
import { importPreviewPage, canConfirmBankImport } from '../src/utils/questionBankImport.js'

test('preview keeps hundreds of rows reachable and filters errors and duplicates', () => {
  const rows = Array.from({ length: 125 }, (_, index) => ({
    rowNumber: index + 2,
    status: index === 53 ? 'ERROR' : index === 99 ? 'DUPLICATE' : 'VALID',
  }))
  const preview = { rows, totalRows: 125, errorCount: 1, duplicateCount: 1 }
  assert.deepEqual(importPreviewPage(preview, 'ALL', 3, 50), { rows: rows.slice(100), total: 125, page: 3, pages: 3 })
  assert.deepEqual(importPreviewPage(preview, 'ERROR', 1, 50).rows, [rows[53]])
  assert.deepEqual(importPreviewPage(preview, 'DUPLICATE', 1, 50).rows, [rows[99]])
  assert.equal(importPreviewPage(preview, 'ALL', 9, 50).page, 3)
})

test('confirmation requires a clean preview and an explicit duplicate choice', () => {
  const preview = { rows: [], totalRows: 3, errorCount: 0, duplicateCount: 1 }
  assert.equal(canConfirmBankImport(preview, ''), false)
  assert.equal(canConfirmBankImport(preview, 'SKIP'), true)
  assert.equal(canConfirmBankImport(preview, 'ALLOW'), true)
  assert.equal(canConfirmBankImport({ ...preview, errorCount: 1 }, 'SKIP'), false)
  assert.equal(canConfirmBankImport({ ...preview, duplicateCount: 0 }, ''), true)
  assert.equal(canConfirmBankImport(null, 'SKIP'), false)
})
