import test from 'node:test'
import assert from 'node:assert/strict'
const draft = await import('../src/utils/studentAnswerDraft.js').catch(() => ({}))
const settle = async () => { for (let n = 0; n < 8; n++) await Promise.resolve() }
function deferred() { let resolve, reject; const promise = new Promise((ok, fail) => { resolve = ok; reject = fail }); return { promise, resolve, reject } }
function timer() {
  let now = 0, next = 0; const tasks = new Map()
  return { now: () => now, schedule: (fn, delay) => { tasks.set(++next, { at: now + delay, fn }); return next }, cancel: id => tasks.delete(id),
    async tick(ms) {
      const target = now + ms
      for (;;) { const due = [...tasks].filter(([, task]) => task.at <= target).sort((a, b) => a[1].at - b[1].at)[0]
        if (!due) break; now = due[1].at; tasks.delete(due[0]); due[1].fn(); await settle()
      }
      now = target; await settle()
    }, get size() { return tasks.size },
  }
}
function queue(save, options = {}) {
  assert.equal(typeof draft.createAnswerSaveQueue, 'function', 'answer queue not implemented')
  const clock = timer(); return { clock, q: draft.createAnswerSaveQueue({ save, ...clock, ...options }) }
}
function storage() { const values = new Map(); return { getItem: k => values.get(k) ?? null, setItem: (k, v) => values.set(k, v), removeItem: k => values.delete(k), key: i => [...values.keys()][i], get length() { return values.size } } }

test('autosave waits for 1000ms of inactivity and saves the latest revision', async () => {
  const calls = [], { q, clock } = queue(async (...args) => calls.push(args))
  q.edit(1, 'a', 1); await clock.tick(900); q.edit(1, 'ab', 2); await clock.tick(999)
  assert.equal(calls.length, 0); assert.equal(q.getState(1).status, 'pending')
  await clock.tick(1); assert.deepEqual(calls[0], [1, 'ab', 2]); assert.equal(q.getState(1).status, 'saved')
  q.dispose()
})

test('one question is serial and a late response cannot mark newer input saved', async () => {
  const first = deferred(), second = deferred(), calls = []
  const { q, clock } = queue((...args) => { calls.push(args); return calls.length === 1 ? first.promise : second.promise })
  q.edit(1, 'old', 1); await clock.tick(1000); q.edit(1, 'new', 2); await clock.tick(1000)
  assert.equal(calls.length, 1); first.resolve(); await settle()
  assert.equal(calls.length, 2); assert.equal(q.getState(1).savedRevision, 1); assert.notEqual(q.getState(1).status, 'saved')
  assert.equal(q.getState(1).value, 'new'); second.resolve(); await settle()
  assert.equal(q.getState(1).savedRevision, 2); assert.equal(q.getState(1).status, 'saved'); q.dispose()
})

test('different questions can complete out of order with at most three concurrent requests', async () => {
  const pending = new Map(), calls = [], { q, clock } = queue(id => { calls.push(id); const request = deferred(); pending.set(id, request); return request.promise })
  for (let id = 1; id <= 5; id++) q.edit(id, String(id))
  await clock.tick(1000); assert.deepEqual(calls, [1, 2, 3])
  pending.get(3).resolve(); await settle(); assert.deepEqual(calls, [1, 2, 3, 4])
  assert.equal(q.getState(3).status, 'saved'); assert.equal(q.getState(1).status, 'saving')
  pending.get(1).resolve(); await settle(); assert.deepEqual(calls, [1, 2, 3, 4, 5])
  for (const value of pending.values()) value.resolve(); await q.flush(); q.dispose()
})

test('temporary network failures retry after 2s then 5s and stop after the third attempt', async () => {
  const calls = [], { q, clock } = queue(async () => { calls.push(clock.now()); throw { errorCode: 'NETWORK_ERROR', message: '离线' } })
  q.edit(1, 'answer'); await clock.tick(1000); assert.deepEqual(calls, [1000])
  await clock.tick(1999); assert.equal(calls.length, 1); await clock.tick(1); assert.deepEqual(calls, [1000, 3000])
  await clock.tick(4999); assert.equal(calls.length, 2); await clock.tick(1); assert.deepEqual(calls, [1000, 3000, 8000])
  await clock.tick(20000); assert.equal(calls.length, 3); assert.equal(q.getState(1).status, 'failed'); q.dispose()
})

for (const errorCode of ['UNAUTHORIZED', 'FORBIDDEN', 'CONFLICT', 'BAD_REQUEST']) {
  test(`${errorCode} is a terminal rejection and never automatically retried`, async () => {
    let calls = 0; const { q, clock } = queue(async () => { calls++; throw { errorCode, message: '业务拒绝' } })
    q.edit(1, 'answer'); await clock.tick(30000)
    assert.equal(calls, 1); assert.equal(q.getState(1).status, 'failed'); q.dispose()
  })
}

test('flush waits for the latest revision while a previous save is still pending', async () => {
  const pending = [], { q, clock } = queue(() => { const request = deferred(); pending.push(request); return request.promise })
  q.edit(1, 'a'); await clock.tick(1000); q.edit(1, 'b'); let finished = false
  const flushed = q.flush().then(() => { finished = true }); await settle(); assert.equal(finished, false)
  pending[0].resolve(); await settle(); assert.equal(pending.length, 2); assert.equal(finished, false)
  pending[1].resolve(); await flushed; assert.equal(q.getState(1).status, 'saved'); q.dispose()
})

test('a failed question rejects flush and explicit retry can recover it', async () => {
  let fail = true; const { q } = queue(async () => { if (fail) throw { errorCode: 'BAD_REQUEST', message: '拒绝' } })
  q.edit(1, 'answer'); await assert.rejects(q.flush(), error => error.questionIds.includes(1))
  fail = false; q.retry(1); await q.flush(); assert.equal(q.getState(1).status, 'saved'); q.dispose()
})
test('an edit made while flush is waiting is included without a second debounce', async () => {
  const first = deferred(), calls = [], { q } = queue((...args) => { calls.push(args); return calls.length === 1 ? first.promise : Promise.resolve() })
  q.edit(1, 'first'); const pending = q.flush().catch(() => {}); await settle(); q.edit(1, 'newer'); first.resolve(); await settle()
  try { assert.equal(calls.length, 2); assert.equal(calls[1][1], 'newer') } finally { q.dispose(); await pending }
})
test('flush waits through the scheduled network retry delay instead of bypassing backoff', async () => {
  const calls = [], { q, clock } = queue(async () => { calls.push(clock.now()); if (calls.length === 1) throw { errorCode: 'NETWORK_ERROR' } })
  q.edit(1, 'answer'); await clock.tick(1000); const pending = q.flush().catch(() => {}); await settle()
  try { assert.deepEqual(calls, [1000]); await clock.tick(2000); await pending; assert.deepEqual(calls, [1000, 3000]) }
  finally { q.dispose(); await pending }
})

test('pause and disposal cancel scheduled writes and disposal ignores in-flight completions', async () => {
  let calls = 0, changes = 0; const request = deferred(), { q, clock } = queue(() => { calls++; return request.promise }, { onChange: () => changes++ })
  q.edit(1, 'a'); q.pause(); await clock.tick(5000); assert.equal(calls, 0)
  await assert.rejects(q.flush()); q.resume(); await clock.tick(1000); assert.equal(calls, 1)
  q.dispose(); const before = changes; request.resolve(); await settle(); assert.equal(changes, before); assert.equal(clock.size, 0)
})

test('user and submission drafts use versioned isolated records and scoped cleanup', () => {
  assert.equal(typeof draft.draftKey, 'function', 'draft storage not implemented'); const s = storage()
  const a = { 9: { value: 'user1', revision: 2 } }, b = { 9: { value: 'user2', revision: 1 } }
  assert.equal(draft.writeDraft(1, 10, a, s), true); draft.writeDraft(2, 10, b, s); draft.writeDraft(1, 11, a, s)
  assert.notEqual(draft.draftKey(1, 10), draft.draftKey(2, 10)); assert.deepEqual(draft.readDraft(1, 10, s).answers, a)
  draft.clearDraft(1, 10, s); assert.equal(draft.readDraft(1, 10, s), null); assert.deepEqual(draft.readDraft(2, 10, s).answers, b)
  draft.clearUserDrafts(1, s); assert.equal(draft.readDraft(1, 11, s), null); assert.deepEqual(draft.readDraft(2, 10, s).answers, b)
})

test('malformed, unknown-version and mismatched-owner draft records are never restored', () => {
  assert.equal(typeof draft.readDraft, 'function', 'draft storage not implemented'); const s = storage(), key = draft.draftKey(1, 10)
  for (const value of ['{', JSON.stringify({ version: 99 }), JSON.stringify({ version: 1, userId: '2', submissionId: '10', answers: { 9: { value: 'private', revision: 1 } } })]) {
    s.setItem(key, value); assert.equal(draft.readDraft(1, 10, s), null)
  }
})

test('storage denied or quota exceeded does not throw or prevent memory-based edits', async () => {
  assert.equal(typeof draft.writeDraft, 'function', 'draft storage not implemented')
  const denied = { getItem() { throw Error('denied') }, setItem() { throw Error('quota') }, removeItem() { throw Error('denied') } }
  assert.equal(draft.writeDraft(1, 10, { 9: { value: 'answer', revision: 1 } }, denied), false)
  assert.equal(draft.readDraft(1, 10, denied), null); assert.equal(draft.clearDraft(1, 10, denied), false)
  const { q } = queue(async () => {}); q.edit(9, 'answer'); await q.flush(); assert.equal(q.getState(9).value, 'answer'); q.dispose()
})
