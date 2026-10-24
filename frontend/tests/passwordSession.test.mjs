import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { parse, compileScript } from 'vue/compiler-sfc'
import { ref } from 'vue'
import * as vue from 'vue'
import { renderToString } from 'vue/server-renderer'

async function passwordPage(change = async () => {}) {
  const source = await readFile(new URL('../src/views/student/ChangePasswordView.vue', import.meta.url), 'utf8')
  const code = compileScript(parse(source).descriptor, { id: 'password', genDefaultAs: 'component' }).content.replace(/import\s*[\s\S]*?from\s*['"][^'"]+['"];?/g, '')
  const authState = { token: 'old-token', currentUser: { id: 1, role: 'STUDENT' }, draftOwner: '1', reauthenticationRequired: false }
  let destination
  const bindings = { ref, useRouter: () => ({ replace: async value => { destination = value } }), changePassword: change, authState,
    restoreSession: async () => {}, expireSession: token => { if (token && token !== authState.token) return; authState.token = ''; authState.currentUser = null; authState.reauthenticationRequired = true } }
  const c = new Function(...Object.keys(bindings), `${code};return component`)(...Object.values(bindings))
  const s = c.setup({}, { expose() {} }); s.currentPassword.value = 'initial-pass'; s.newPassword.value = s.confirmPassword.value = 'changed-pass-123'
  return { s, authState, get destination() { return destination } }
}

test('successful password change expires the session and requires a fresh login while preserving draft ownership', async () => {
  const p = await passwordPage(); await p.s.submit()
  assert.equal(p.authState.token, ''); assert.equal(p.authState.reauthenticationRequired, true)
  assert.equal(p.authState.draftOwner, '1'); assert.deepEqual(p.destination, { path: '/login', query: { passwordChanged: '1' } })
  assert.equal(p.s.currentPassword.value, ''); assert.equal(p.s.newPassword.value, '')
})
test('failed password change keeps the current session and entered values for correction', async () => {
  const p = await passwordPage(async () => { throw Error('当前密码不正确') }); await p.s.submit()
  assert.equal(p.authState.token, 'old-token'); assert.equal(p.destination, undefined)
  assert.equal(p.s.currentPassword.value, 'initial-pass'); assert.match(p.s.error.value, /当前密码不正确/)
})
test('a late successful password request cannot clear a newly authenticated identity', async () => {
  let finish; const pending = new Promise(resolve => { finish = resolve }), p = await passwordPage(() => pending)
  const saving = p.s.submit(); p.authState.token = 'other-token'; p.authState.currentUser = { id: 2, role: 'STUDENT' }
  finish(); await saving; assert.equal(p.authState.token, 'other-token'); assert.equal(p.destination, undefined)
})

test('login renders the successful password-change notice after returning for authentication', async () => {
  const source = await readFile(new URL('../src/views/LoginView.vue', import.meta.url), 'utf8')
  const code = compileScript(parse(source).descriptor, { id: 'login-notice', inlineTemplate: true }).content
    .replace(/import\s*{([^}]+)}\s*from\s*['"]([^'"]+)['"];?/g, (_, names, from) => `const {${names.replace(/\bas\b/g, ':')}} = imports[${JSON.stringify(from)}];`)
    .replace('export default', 'const component =')
  const imports = { vue, 'vue-router': { useRoute: () => ({ query: { passwordChanged: '1' } }), useRouter: () => ({}) }, '../api/auth': { login() {} }, '../auth/auth': { authState: {}, setSession() {} }, '../utils/returnPath': { safeReturnPath: value => value } }
  const component = new Function('imports', `${code};return component`)(imports), app = vue.createSSRApp(component)
  app.component('RouterLink', { render: () => null })
  assert.match(await renderToString(app), /修改成功，请重新登录/)
})
