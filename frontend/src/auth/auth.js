import { reactive } from 'vue'
export const TOKEN_KEY = 'smart_exam_token'
export const authState = reactive({ token: sessionStorage.getItem(TOKEN_KEY) || '', currentUser: null, loading: false })
export function setSession(token, user) { authState.token = token; authState.currentUser = user; sessionStorage.setItem(TOKEN_KEY, token) }
export function clearSession() { authState.token = ''; authState.currentUser = null; sessionStorage.removeItem(TOKEN_KEY) }
export async function restoreSession() { if (!authState.token || authState.loading) return authState.currentUser; authState.loading = true; try { const { getCurrentUser } = await import('../api/auth'); const result = await getCurrentUser(); authState.currentUser = result.data; return result.data } catch { clearSession(); return null } finally { authState.loading = false } }
