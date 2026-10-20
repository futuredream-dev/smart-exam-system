import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { parse, compileScript } from 'vue/compiler-sfc'
import { computed, ref } from 'vue'
import { safeReturnPath } from '../src/utils/returnPath.js'
async function destination(returnTo, user, previousOwner = '1') {
  const source = await readFile(new URL('../src/views/LoginView.vue', import.meta.url), 'utf8')
  const code = compileScript(parse(source).descriptor, { id: 'login', genDefaultAs: 'component' }).content.replace(/import\s*[\s\S]*?from\s*['"][^'"]+['"];?/g, '')
  const component = new Function('computed', 'ref', 'useRoute', 'useRouter', 'safeReturnPath', 'authState', 'login', 'setSession', `${code};return component`)(computed, ref, () => ({ query: { returnTo } }), () => ({}), safeReturnPath, { draftOwner: previousOwner }, () => {}, () => {})
  return component.setup({}, { expose() {} }).destination(user, previousOwner)
}
test('same student login can return to its paper while identity switches go home', async () => {
  assert.equal(await destination('/student/paper/10', { id: 1, role: 'STUDENT' }), '/student/paper/10')
  assert.equal(await destination('/student/paper/10', { id: 2, role: 'STUDENT' }), '/student')
})
test('external and cross-role return paths are rejected and required password change wins', async () => {
  for (const path of ['https://evil.test', '//evil.test', '/teacher/exams']) assert.equal(await destination(path, { id: 1, role: 'STUDENT' }), '/student')
  assert.equal(await destination('/student/paper/10', { id: 1, role: 'STUDENT', mustChangePassword: true }), '/student/change-password')
})
