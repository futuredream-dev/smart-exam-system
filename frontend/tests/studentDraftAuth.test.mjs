import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { reactive } from 'vue'
import { clearUserDrafts, writeDraft, readDraft } from '../src/utils/studentAnswerDraft.js'
function storage() { const m = new Map(); return { getItem: k => m.get(k) ?? null, setItem: (k, v) => m.set(k, v), removeItem: k => m.delete(k), key: i => [...m.keys()][i], get length() { return m.size } } }
async function auth(s, getCurrentUser = async () => ({ data: { id: 1 } })) {
  const source = (await readFile(new URL('../src/auth/auth.js', import.meta.url), 'utf8')).replace(/import\s*[^\n]*from[^\n]*\n/g, '').replace(/export /g, '').replace("import('../api/auth')", 'Promise.resolve({getCurrentUser:getSessionUser})')
  return new Function('reactive', 'clearUserDrafts', 'sessionStorage', 'getSessionUser', `${source}; return {authState,setSession,clearSession,restoreSession,expireSession: typeof expireSession==='function'?expireSession:null}`)(reactive, clearUserDrafts, s, getCurrentUser)
}
test('explicit logout clears only the current user drafts', async () => {
  const s = storage(), a = await auth(s); a.setSession('test-token', { id: 1 });
  writeDraft(1, 10, { 9: { value: 'private', revision: 1 } }, s); writeDraft(2, 10, { 9: { value: 'other', revision: 1 } }, s)
  a.clearSession(); assert.equal(readDraft(1, 10, s), null); assert.ok(readDraft(2, 10, s)); assert.equal(a.authState.token, '')
})
test('session expiry preserves recovery for same user but switching identity clears it', async () => {
  const s = storage(), a = await auth(s); a.setSession('test-token', { id: 1 })
  writeDraft(1, 10, { 9: { value: 'private', revision: 1 } }, s)
  assert.equal(typeof a.expireSession, 'function'); a.expireSession(); assert.ok(readDraft(1, 10, s))
  a.setSession('new-token', { id: 1 }); assert.ok(readDraft(1, 10, s)); a.expireSession()
  // After a page reload currentUser is unknown, but the scoped previous owner is still tracked.
  const reloaded = await auth(s); reloaded.setSession('other-token', { id: 2 }); assert.equal(readDraft(1, 10, s), null)
})
test('denied session storage still allows a memory-based login and logout', async () => {
  const s = { getItem() { throw Error('denied') }, setItem() { throw Error('denied') }, removeItem() { throw Error('denied') } }
  const a = await auth(s); a.setSession('test-token', { id: 1 }); assert.equal(a.authState.token, 'test-token'); a.clearSession(); assert.equal(a.authState.token, '')
})
test('a late 401 from an old token cannot expire a newly authenticated identity', async () => {
  const a = await auth(storage()); a.setSession('old-token', { id: 1 }); a.setSession('new-token', { id: 2 })
  a.expireSession('old-token'); assert.equal(a.authState.token, 'new-token'); assert.equal(a.authState.currentUser.id, 2)
})
test('a delayed session restoration cannot replace a newer login or clear its drafts', async () => {
  let finish; const pending = new Promise(resolve => { finish = resolve }), s = storage(), a = await auth(s, () => pending)
  a.setSession('old-token', { id: 1 }); const restore = a.restoreSession(); await Promise.resolve()
  a.setSession('new-token', { id: 2 }); writeDraft(2, 10, { 9: { value: 'new draft', revision: 1 } }, s)
  finish({ data: { id: 1 } }); await restore
  assert.equal(a.authState.currentUser.id, 2); assert.ok(readDraft(2, 10, s))
})
