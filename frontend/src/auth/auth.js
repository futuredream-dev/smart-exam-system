import { reactive } from 'vue'
import { clearUserDrafts } from '../utils/studentAnswerDraft'
export const TOKEN_KEY = 'smart_exam_token'
const OWNER_KEY = 'smart_exam_draft_owner'
const stored = key => { try { return sessionStorage.getItem(key) || '' } catch { return '' } }
function store(key, value) { try { if (value) sessionStorage.setItem(key, value); else sessionStorage.removeItem(key) } catch { /* The current tab can still use memory-based auth. */ } }
function discardDrafts(id) { try { if (id) clearUserDrafts(id, sessionStorage) } catch {} }
export const authState = reactive({ token: stored(TOKEN_KEY), currentUser: null, loading: false, draftOwner: stored(OWNER_KEY), reauthenticationRequired: false })
let generation = 0, identityVersion = 0
export function sessionIdentityVersion() { return identityVersion }
export function setSession(token, user) {
  const previous = authState.currentUser?.id ?? authState.draftOwner
  if (previous && String(previous) !== String(user.id)) { discardDrafts(previous); store('studentSubmitSuccess', '') }
  generation++; identityVersion++; authState.token = token; authState.currentUser = user; authState.draftOwner = String(user.id); authState.reauthenticationRequired = false
  store(TOKEN_KEY, token); store(OWNER_KEY, String(user.id))
}
export function clearSession() {
  const previous = authState.currentUser?.id ?? authState.draftOwner
  // Revoke persistence ownership before synchronous page observers can write a paused queue.
  generation++; identityVersion++; authState.draftOwner = ''; authState.currentUser = null; authState.token = ''; authState.reauthenticationRequired = false
  discardDrafts(previous)
  store(TOKEN_KEY, ''); store(OWNER_KEY, ''); store('studentSubmitSuccess', '')
}
export function expireSession(expectedToken) {
  if (expectedToken && expectedToken !== authState.token) return
  generation++; authState.token = ''; authState.currentUser = null; authState.reauthenticationRequired = true
  store(TOKEN_KEY, '')
}
export async function restoreSession() {
  if (!authState.token || authState.loading) return authState.currentUser
  const currentGeneration = generation; authState.loading = true
  try {
    const { getCurrentUser } = await import('../api/auth'); const result = await getCurrentUser()
    if (currentGeneration !== generation) return authState.currentUser
    setSession(authState.token, result.data); return result.data
  } catch { if (currentGeneration === generation) expireSession(); return null }
  finally { authState.loading = false }
}
