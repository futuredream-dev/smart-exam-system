import test from 'node:test'
import assert from 'node:assert/strict'
const clock = await import('../src/utils/examClock.js').catch(() => ({}))
const wait = async () => { for (let n = 0; n < 8; n++) await Promise.resolve() }
test('countdown uses server instants and elapsed monotonic time despite a fast or slow wall clock', () => {
  assert.equal(typeof clock.remainingMillis, 'function', 'exam clock not implemented')
  const original = Date.now
  try { for (const skew of [-300000, 300000]) { Date.now = () => Date.parse('2026-10-20T02:00:00Z') + skew
    assert.equal(clock.remainingMillis('2026-10-20T02:00:00Z', '2026-10-20T02:01:00Z', 1500), 58500)
  } } finally { Date.now = original }
  assert.equal(clock.remainingMillis('2026-10-20T02:00:00Z', '2026-10-20T02:01:00Z', 61000), 0)
})
test('a fresh server anchor resynchronizes countdown without negative elapsed time', () => {
  assert.equal(typeof clock.remainingMillis, 'function')
  assert.equal(clock.remainingMillis('2026-10-20T02:00:30Z', '2026-10-20T02:01:00Z', 1000), 29000)
  assert.equal(clock.remainingMillis('2026-10-20T02:00:30Z', '2026-10-20T02:01:00Z', -1000), 30000)
})
function refresher(load, extra = {}) {
  assert.equal(typeof clock.createBoundaryRefresh, 'function', 'boundary refresher not implemented')
  let timer, count = 0; const seen = [], errors = []
  const runner = clock.createBoundaryRefresh({ load, onData: value => seen.push(value), onError: error => errors.push(error),
    schedule: (fn, delay) => { assert.equal(timer, undefined, 'only one timer may exist'); timer = { fn, delay }; return ++count }, cancel: () => { timer = undefined }, ...extra })
  return { runner, seen, errors, fire: async () => { const current = timer; timer = undefined; current.fn(); await wait() }, get timer() { return timer } }
}
const upcoming = { serverNow: '2026-10-20T02:00:00Z', startsAt: '2026-10-20T02:00:01Z', entryDeadlineAt: '2026-10-20T02:05:00Z', deadlineAt: '2026-10-20T02:10:00Z', lifecycleStatus: 'NOT_STARTED' }
test('start and entry boundaries trigger a refresh and repeated refreshes never duplicate timers', async () => {
  let calls = 0; const f = refresher(async () => { calls++; return [upcoming] })
  await f.runner.refresh(); assert.ok(f.timer.delay >= 1000 && f.timer.delay < 1300)
  await f.runner.refresh(); assert.equal(calls, 2); await f.fire(); assert.equal(calls, 3); f.runner.dispose(); assert.equal(f.timer, undefined)
})
test('inactive or empty exam lists have no continuous refresh timer', async () => {
  for (const data of [[], [{ ...upcoming, lifecycleStatus: 'RESULTS_RELEASED' }]]) {
    const f = refresher(async () => data); await f.runner.refresh(); assert.equal(f.timer, undefined); f.runner.dispose()
  }
})
test('older response cannot overwrite newer state and disposal ignores late data', async () => {
  const pending = []; const f = refresher(() => new Promise(resolve => pending.push(resolve)))
  const first = f.runner.refresh(), second = f.runner.refresh(); pending[1]([]); await second; pending[0]([upcoming]); await first
  assert.deepEqual(f.seen, [[]]); const late = f.runner.refresh(); f.runner.dispose(); pending[2]([upcoming]); await late; assert.deepEqual(f.seen, [[]])
})
test('refresh errors preserve last successful data and expose an error', async () => {
  let failing = false; const f = refresher(async () => { if (failing) throw Error('offline'); return [upcoming] })
  await f.runner.refresh(); failing = true; await f.runner.refresh()
  assert.equal(f.seen.length, 1); assert.equal(f.errors.length, 1); assert.ok(f.timer.delay >= 5000); f.runner.dispose()
})
test('boundary scheduling accounts conservatively for delayed response time', async () => {
  let mono = 0, finish; const pending = new Promise(resolve => { finish = resolve }), f = refresher(() => pending, { now: () => mono })
  const loading = f.runner.refresh(); mono = 5000; finish([upcoming]); await loading
  assert.ok(f.timer.delay <= 100); f.runner.dispose()
})
