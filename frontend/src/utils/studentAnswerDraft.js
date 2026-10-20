const PREFIX = 'smart_exam_answer_draft:v1:'
const RETRY_DELAYS = [2000, 5000]
export const transientSaveError = error => ['NETWORK_ERROR', 'REQUEST_TIMEOUT'].includes(error?.errorCode)

export function createAnswerSaveQueue({ save, now = Date.now, schedule = setTimeout, cancel = clearTimeout, maxConcurrent = 3, onChange = () => {} }) {
  const entries = new Map(), waiters = new Set()
  let active = 0, paused = false, disposed = false
  const limit = Math.max(1, Math.min(3, maxConcurrent))
  const snapshot = e => ({ id: e.id, value: e.value, revision: e.revision, savedRevision: e.savedRevision,
    savedValue: e.savedValue, status: e.status, error: e.error, inFlight: e.inFlight, updatedAt: e.updatedAt })
  const getState = id => id === undefined ? Object.fromEntries([...entries].map(([key, e]) => [key, snapshot(e)])) : entries.has(id) ? snapshot(entries.get(id)) : null
  function changed() { if (!disposed) onChange(getState()) }
  function unsaved() { return [...entries.values()].filter(e => e.savedRevision < e.revision) }
  function failure(message, ids = []) { return Object.assign(new Error(message), { questionIds: ids }) }
  function settleFlushes() {
    const dirty = unsaved(), failed = dirty.filter(e => e.status === 'failed')
    if (!disposed && !paused && dirty.length && !failed.length) return
    for (const waiter of waiters) {
      if (disposed || paused || failed.length) waiter.reject(failure(disposed ? '保存队列已关闭' : paused ? '保存已暂停' : '部分答案未保存，请重试', failed.map(e => e.id)))
      else waiter.resolve()
    }
    waiters.clear()
  }
  function stopTimer(e) { if (e.timer !== null) cancel(e.timer); e.timer = null; e.timerKind = null }
  function arm(e, delay, kind = 'debounce') {
    stopTimer(e)
    if (paused || disposed) return
    e.ready = false; e.timerKind = kind
    e.timer = schedule(() => { e.timer = null; e.timerKind = null; e.ready = true; pump() }, delay)
  }
  function pump() {
    if (disposed || paused) { settleFlushes(); return }
    for (const e of entries.values()) {
      if (active >= limit) break
      if (!e.ready || e.inFlight || e.savedRevision === e.revision || e.status === 'failed') continue
      stopTimer(e); e.ready = false; e.inFlight = true; e.status = 'saving'; active++
      const sent = { revision: e.revision, value: e.value }
      Promise.resolve().then(() => save(e.id, sent.value, sent.revision)).then(() => {
        if (disposed) return
        e.savedRevision = sent.revision; e.savedValue = sent.value; e.error = null
        e.status = e.revision === sent.revision ? 'saved' : 'pending'
        e.attempts = 0
      }, error => {
        if (disposed) return
        if (e.revision !== sent.revision) { e.status = 'pending'; return }
        e.error = error; e.status = 'failed'
        if (!paused && transientSaveError(error) && e.attempts < RETRY_DELAYS.length) {
          e.status = 'pending'; arm(e, RETRY_DELAYS[e.attempts++], 'retry')
        }
      }).finally(() => {
        active--; e.inFlight = false
        if (disposed) return
        e.updatedAt = now(); changed(); pump(); settleFlushes()
      })
    }
    changed(); settleFlushes()
  }
  function edit(id, value, revision) {
    if (disposed) return
    let e = entries.get(id)
    if (!e) { e = { id, value: '', revision: 0, savedRevision: 0, savedValue: '', timer: null, inFlight: false }; entries.set(id, e) }
    const next = revision ?? e.revision + 1
    if (!Number.isSafeInteger(next) || next <= e.revision) return
    e.value = String(value ?? ''); e.revision = next; e.attempts = 0; e.error = null; e.status = 'pending'; e.ready = false; e.updatedAt = now()
    if (waiters.size && !paused) { stopTimer(e); e.ready = true; pump() }
    else arm(e, 1000)
    changed()
  }
  function flush() {
    if (disposed || paused) return Promise.reject(failure('保存已暂停'))
    for (const e of unsaved()) {
      if (e.timerKind === 'retry') continue
      stopTimer(e); if (e.status !== 'failed') e.ready = true
    }
    const pending = new Promise((resolve, reject) => waiters.add({ resolve, reject }))
    pump(); return pending
  }
  function retry(id) {
    if (disposed || paused) return
    for (const e of unsaved()) if (id === undefined || e.id === id) {
      stopTimer(e); e.error = null; e.attempts = 0; e.status = 'pending'; e.ready = true
    }
    pump()
  }
  function pause() { paused = true; for (const e of entries.values()) { stopTimer(e); e.ready = false } changed(); settleFlushes() }
  function resume() { if (disposed) return; paused = false; for (const e of unsaved()) if (e.status !== 'failed') arm(e, 1000); changed() }
  function dispose() { disposed = true; for (const e of entries.values()) stopTimer(e); settleFlushes() }
  return { edit, flush, retry, dispose, getState, pause, resume }
}

export function draftKey(userId, submissionId) {
  if (!/^[1-9]\d*$/.test(String(userId)) || !/^[1-9]\d*$/.test(String(submissionId))) throw new Error('Invalid draft owner')
  return `${PREFIX}${userId}:${submissionId}`
}
function validAnswers(answers) {
  return answers && typeof answers === 'object' && !Array.isArray(answers) && Object.entries(answers).every(([id, entry]) =>
    /^[1-9]\d*$/.test(id) && entry && typeof entry.value === 'string' && Number.isSafeInteger(entry.revision) && entry.revision > 0)
}
export function readDraft(userId, submissionId, storage) {
  try {
    const record = JSON.parse((storage ?? globalThis.sessionStorage).getItem(draftKey(userId, submissionId)))
    return record?.version === 1 && record.userId === String(userId) && record.submissionId === String(submissionId) && validAnswers(record.answers) ? record : null
  } catch { return null }
}
export function writeDraft(userId, submissionId, answers, storage) {
  try {
    if (!validAnswers(answers)) return false
    ;(storage ?? globalThis.sessionStorage).setItem(draftKey(userId, submissionId), JSON.stringify({ version: 1, userId: String(userId), submissionId: String(submissionId), answers, updatedAt: Date.now() }))
    return true
  } catch { return false }
}
export function clearDraft(userId, submissionId, storage) {
  try { (storage ?? globalThis.sessionStorage).removeItem(draftKey(userId, submissionId)); return true } catch { return false }
}
export function clearUserDrafts(userId, storage) {
  try {
    const target = storage ?? globalThis.sessionStorage, prefix = `${PREFIX}${userId}:`, keys = []
    for (let index = 0; index < target.length; index++) { const key = target.key(index); if (key?.startsWith(prefix)) keys.push(key) }
    keys.forEach(key => target.removeItem(key)); return true
  } catch { return false }
}
