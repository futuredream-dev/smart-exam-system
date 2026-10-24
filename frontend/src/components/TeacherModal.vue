<script setup>
import { nextTick, onMounted, onUnmounted, ref, useId } from 'vue'

const props = defineProps({
  title: { type: String, default: '' },
  size: { type: String, default: 'form', validator: value => ['confirm', 'form', 'wide'].includes(value) },
  variant: { type: String, default: 'default', validator: value => ['default', 'danger'].includes(value) },
  busy: { type: Boolean, default: false },
  dirty: { type: Boolean, default: false },
})
const emit = defineEmits(['close', 'discard-request', 'discard-cancel'])
const discardOpen = ref(false), discardCancel = ref(null)
const panel = ref(null)
const heading = ref(null)
const titleId = useId()
let previousFocus = null

function requestClose() {
  if (props.busy) return
  if (discardOpen.value) { cancelDiscard(); return }
  if (!props.dirty) { emit('close'); return }
  discardOpen.value = true
  emit('discard-request')
  nextTick(() => discardCancel.value?.focus())
}
function cancelDiscard() { if (!props.busy) { discardOpen.value = false; emit('discard-cancel'); nextTick(() => heading.value?.focus()) } }
function confirmDiscard() { if (!props.busy && discardOpen.value) emit('close') }
defineExpose({ requestClose })

function focusableElements() {
  return [...(panel.value?.querySelectorAll('a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])') || [])]
    .filter(element => element.getClientRects().length > 0)
}

function onKeydown(event) {
  if (event.key === 'Escape') {
    event.preventDefault()
    event.stopPropagation()
    requestClose()
    return
  }
  if (event.key !== 'Tab') return
  const elements = focusableElements()
  if (!elements.length) {
    event.preventDefault()
    heading.value?.focus()
    return
  }
  const first = elements[0]
  const last = elements[elements.length - 1]
  if (!panel.value?.contains(document.activeElement)) {
    event.preventDefault()
    const target = event.shiftKey ? last : first
    target.focus()
    return
  }
  if (event.shiftKey && (document.activeElement === first || document.activeElement === heading.value)) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

onMounted(async () => {
  previousFocus = document.activeElement
  window.addEventListener('keydown', onKeydown)
  await nextTick()
  heading.value?.focus()
})
onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown)
  nextTick(() => {
    if (previousFocus?.isConnected) previousFocus.focus()
  })
})
</script>

<template>
  <div class="modal-backdrop" @click.self="requestClose">
    <section ref="panel" class="modal-panel" :class="[`modal-panel--${size}`, { 'modal-panel--danger': variant === 'danger' }]"
      role="dialog" aria-modal="true" :aria-labelledby="titleId" :aria-busy="busy">
      <header class="section-head modal-header">
        <h2 :id="titleId" ref="heading" tabindex="-1">{{ title }}</h2>
        <button type="button" class="ghost" aria-label="关闭弹窗" :disabled="busy" @click="requestClose">关闭</button>
      </header>
      <div v-if="discardOpen" class="modal-body confirmation-copy" role="alert"><h3>放弃未保存的修改？</h3><p>关闭后将丢失本次输入，选择“继续编辑”可保留内容。</p><div class="actions"><button ref="discardCancel" type="button" class="secondary" :disabled="busy" @click="cancelDiscard">继续编辑</button><button type="button" class="danger" :disabled="busy" @click="confirmDiscard">放弃修改</button></div></div>
      <div v-show="!discardOpen" class="modal-body"><slot /></div>
      <footer v-if="$slots.footer" v-show="!discardOpen" class="modal-footer"><slot name="footer" /></footer>
    </section>
  </div>
</template>
