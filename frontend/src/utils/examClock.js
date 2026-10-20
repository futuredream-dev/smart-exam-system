export function remainingMillis(serverNow, deadlineAt, elapsedMonotonic) {
  const remaining = Date.parse(deadlineAt) - Date.parse(serverNow) - Math.max(0, elapsedMonotonic)
  return Number.isFinite(remaining) ? Math.max(0, remaining) : 0
}
export function createBoundaryRefresh({ load, onData, onError, schedule = setTimeout, cancel = clearTimeout, now = () => performance.now() }) {
  let timer = null, version = 0, disposed = false, lastData
  const rows = data => Array.isArray(data) ? data : data ? [data] : []
  const active = data => rows(data).filter(row => ['NOT_STARTED', 'AVAILABLE', 'IN_PROGRESS'].includes(row.lifecycleStatus))
  function stop() { if (timer !== null) cancel(timer); timer = null }
  function arm(delay) { stop(); if (!disposed && Number.isFinite(delay)) timer = schedule(() => { timer = null; void refresh() }, Math.min(2_000_000_000, Math.max(100, delay))) }
  function nextDelay(data) {
    const deltas = active(data).flatMap(row => {
      const fields = row.lifecycleStatus === 'NOT_STARTED' ? ['startsAt', 'entryDeadlineAt', 'deadlineAt'] : row.lifecycleStatus === 'AVAILABLE' ? ['entryDeadlineAt', 'deadlineAt'] : ['deadlineAt']
      return fields.map(field => Date.parse(row[field]) - Date.parse(row.serverNow)).filter(value => Number.isFinite(value) && value >= 0)
    })
    return deltas.length ? Math.min(...deltas) + 100 : Infinity
  }
  async function refresh() {
    if (disposed) return
    stop(); const request = ++version, startedAt = now()
    try {
      const data = await load()
      if (disposed || request !== version) return
      lastData = data; onData(data); arm(nextDelay(data) - Math.max(0, now() - startedAt))
    } catch (error) {
      if (disposed || request !== version) return
      onError(error)
      if (active(lastData).length) arm(30000)
    }
  }
  return { refresh, dispose() { disposed = true; version++; stop() } }
}
