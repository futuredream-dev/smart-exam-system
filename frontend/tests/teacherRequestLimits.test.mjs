import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

test('AI grading and assembly requests use their own processing deadlines', async () => {
  const source = await readFile(new URL('../src/api/teacher.js', import.meta.url), 'utf8')
  const code = source.replace(/^import[^\n]+\n/gm, '').replace(/^export /gm, '')
  const requests = [], http = { post: (...args) => { requests.push(args); return Promise.resolve({ data: {} }) } }
  const api = new Function('http', `${code};return teacherApi`)(http)
  await api.aiGrade(17); await api.interpretAiAssembly(3, '需求')
  assert.equal(requests[0][2]?.timeout, 40000)
  assert.equal(requests[1][2]?.timeout, 65000)
})
