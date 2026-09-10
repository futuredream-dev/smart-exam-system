<script setup>
defineProps({ title: String })
defineEmits(['close'])
</script>
<template>
  <div class="modal-backdrop" @click.self="$emit('close')">
    <section class="modal-panel" role="dialog" aria-modal="true" :aria-label="title">
      <header class="section-head"><h2>{{ title }}</h2><button type="button" class="ghost" aria-label="关闭弹窗" @click="$emit('close')">关闭</button></header>
      <slot />
    </section>
  </div>
</template>
