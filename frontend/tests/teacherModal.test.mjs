import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { parse, compileScript } from 'vue/compiler-sfc'
import { ref, reactive, nextTick } from 'vue'

async function modal(values = {}) {
  const source = await readFile(new URL('../src/components/TeacherModal.vue', import.meta.url), 'utf8')
  const compiled = compileScript(parse(source).descriptor, { id: 'modal', genDefaultAs: 'component' })
  const code = compiled.content.replace(/^import .*$/gm, '')
  const component = new Function('ref','nextTick','onMounted','onUnmounted','useId', `${code}; return component`)(ref, nextTick, () => {}, () => {}, () => 'test-modal')
  const props = reactive({ busy: false, dirty: false, ...values }), events = []
  const state = component.setup(props, { expose() {}, emit: (...event) => events.push(event) })
  return { state, props, events }
}
test('dirty close requests confirmation and cancelling never emits close', async () => {
  const { state: s, events } = await modal({ dirty: true })
  s.requestClose()
  assert.deepEqual(events, [['discard-request']]); assert.equal(s.discardOpen.value, true)
  s.cancelDiscard(); assert.equal(s.discardOpen.value, false); assert.equal(events.some(event => event[0] === 'close'), false)
  s.requestClose(); s.confirmDiscard(); assert.equal(events.filter(event => event[0] === 'close').length, 1)
})
test('busy blocks all dismissal paths and clean legacy callers close directly', async () => {
  const { state: s, props, events } = await modal({ dirty: true, busy: true })
  s.requestClose(); assert.deepEqual(events, [])
  if (s.confirmDiscard) s.confirmDiscard(); assert.deepEqual(events, [])
  props.busy = false; props.dirty = false; s.requestClose(); assert.deepEqual(events, [['close']])
})
