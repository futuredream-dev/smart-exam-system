import test from 'node:test'
import assert from 'node:assert/strict'
import { eventTypeNames, filterMonitorStudents, monitorIsRunning, studentStatusNames } from '../src/utils/examMonitor.js'

const students = [
  { studentName: 'Alice', studentNo: 'S001', displayStatus: 'NOT_ENTERED', eventCount: 0 },
  { studentName: '李同学', studentNo: 'S002', displayStatus: 'IN_PROGRESS', eventCount: 2 },
  { studentName: '王同学', studentNo: 'S003', displayStatus: 'SUBMITTED', eventCount: 0 },
  { studentName: '陈同学', studentNo: 'S004', displayStatus: 'AUTO_SUBMIT_PENDING', eventCount: 1 },
]

test('monitor status and event labels retain backend semantics', () => {
  assert.equal(studentStatusNames.NOT_ENTERED, '未进入')
  assert.equal(studentStatusNames.IN_PROGRESS, '考试中')
  assert.equal(studentStatusNames.AUTO_SUBMIT_PENDING, '待自动提交')
  assert.equal(studentStatusNames.SUBMITTED, '已交卷')
  assert.equal(studentStatusNames.AUTO_SUBMITTED, '自动提交')
  assert.equal(studentStatusNames.GRADED, '已阅卷')
  assert.equal(eventTypeNames.TAB_HIDDEN, '切换或隐藏考试页面')
  assert.equal(eventTypeNames.PAGE_LEAVE, '离开考试页面')
  assert.equal(eventTypeNames.RE_ENTER, '重新进入考试')
})

test('monitor search trims input and matches name or student number without case', () => {
  assert.deepEqual(filterMonitorStudents(students, '  ALI ', 'ALL').map(row => row.studentNo), ['S001'])
  assert.deepEqual(filterMonitorStudents(students, ' s002 ', 'ALL').map(row => row.studentName), ['李同学'])
})

test('monitor filters use display status and event count', () => {
  assert.deepEqual(filterMonitorStudents(students, '', 'NOT_ENTERED').map(row => row.studentNo), ['S001'])
  assert.deepEqual(filterMonitorStudents(students, '', 'IN_PROGRESS').map(row => row.studentNo), ['S002'])
  assert.deepEqual(filterMonitorStudents(students, '', 'SUBMITTED').map(row => row.studentNo), ['S003', 'S004'])
  assert.deepEqual(filterMonitorStudents(students, '', 'HAS_EVENTS').map(row => row.studentNo), ['S002', 'S004'])
  assert.deepEqual(filterMonitorStudents(students, '王', 'HAS_EVENTS'), [])
})

test('polling recognizes the runtime phase actually returned by the backend', () => {
  assert.equal(monitorIsRunning('RUNNING'), true)
  assert.equal(monitorIsRunning('IN_PROGRESS'), true)
  assert.equal(monitorIsRunning('ENDED'), false)
})
