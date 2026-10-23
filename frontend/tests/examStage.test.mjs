import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { examDisplayStage, statusLabel, statusTone } from '../src/utils/statusDisplay.js'

const displayUrl = new URL('../src/utils/statusDisplay.js', import.meta.url).href
const source = (await readFile(new URL('../src/utils/examCenter.js', import.meta.url), 'utf8'))
  .replace("'./statusDisplay'", JSON.stringify(displayUrl))
const { actionRoute, stageTabs } = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'))

test('no participants can be found in completed exams and displays a readable neutral state', () => {
  assert.equal(stageTabs.find(tab => tab.key === 'completed').stages.includes('NO_PARTICIPANTS'), true)
  assert.equal(statusLabel(examDisplayStage, 'NO_PARTICIPANTS'), '无人参加')
  assert.equal(statusTone(examDisplayStage, 'NO_PARTICIPANTS'), 'neutral')
  assert.equal(actionRoute(42, 'VIEW'), '/teacher/exams/42')
})
