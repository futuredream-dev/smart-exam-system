import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRouter, createMemoryHistory, isNavigationFailure, NavigationFailureType } from 'vue-router'
import { safeReturnPath } from '../src/utils/returnPath.js'

async function createTestRouter() {
  const source = (await readFile(new URL('../src/router/index.js', import.meta.url), 'utf8'))
    .replace(/^import (\w+) from .*\.vue'$/gm, 'const $1 = {}')
    .replace(/^import .*$/gm, '')
    .replace('export default router', 'return router')
  const document = { title: '' }
  const router = new Function('createRouter', 'createWebHistory', 'authState', 'restoreSession', 'safeReturnPath', 'document', source)(
    createRouter, createMemoryHistory, { currentUser: { id: 1, role: 'STUDENT' } }, async () => {}, safeReturnPath, document,
  )
  await router.push('/student/paper/10')
  return { router, document }
}

test('cancelling navigation keeps the title of the answer page still on screen', async () => {
  const { router, document } = await createTestRouter()
  router.beforeEach(to => to.path === '/student' ? false : undefined)
  const failure = await router.push('/student')
  assert.equal(isNavigationFailure(failure, NavigationFailureType.aborted), true)
  assert.equal(router.currentRoute.value.path, '/student/paper/10')
  assert.equal(document.title, '在线答题 - 智能考试管理系统')
})

test('completed navigation updates the title to the destination page', async () => {
  const { router, document } = await createTestRouter()
  await router.push('/student')
  assert.equal(document.title, '我的考试 - 智能考试管理系统')
})
