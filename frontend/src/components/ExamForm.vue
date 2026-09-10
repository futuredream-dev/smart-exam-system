<script setup>
import { reactive, ref } from 'vue'
import { teacherApi } from '../api/teacher'
const props = defineProps({ courseId: [Number, String], exam: Object })
const emit = defineEmits(['saved'])
const form = reactive({
  title: props.exam?.title || '', description: props.exam?.description || '',
  startTime: props.exam?.startTime?.slice(0, 16) || '', endTime: props.exam?.endTime?.slice(0, 16) || '',
  lateEntryMinutes: props.exam?.lateEntryMinutes ?? 0, minSubmitMinutes: props.exam?.minSubmitMinutes ?? 0,
})
const busy = ref(false), error = ref('')
async function save() {
  error.value = ''
  const duration = (new Date(form.endTime) - new Date(form.startTime)) / 60000
  if (!form.title.trim() || !Number.isFinite(duration) || duration <= 0) { error.value = '请填写标题，并确保结束时间晚于开始时间'; return }
  if ([form.lateEntryMinutes, form.minSubmitMinutes].some(x => !Number.isInteger(x) || x < 0 || x > duration)) { error.value = '规则分钟数必须为非负整数且不能超过考试总时长'; return }
  busy.value = true
  try {
    const payload = { ...form, startTime: form.startTime.length === 16 ? form.startTime + ':00' : form.startTime, endTime: form.endTime.length === 16 ? form.endTime + ':00' : form.endTime }
    const result = await teacherApi.saveExam(props.exam?.id, props.courseId, payload)
    emit('saved', result.data)
  } catch (e) { error.value = e.message } finally { busy.value = false }
}
</script>
<template>
  <form class="editor-form" @submit.prevent="save">
    <label>考试标题<input v-model="form.title" required maxlength="160" /></label>
    <label>考试说明<textarea v-model="form.description" rows="3" maxlength="10000" /></label>
    <div class="form-grid">
      <label>开始时间<input v-model="form.startTime" type="datetime-local" required /></label>
      <label>结束时间<input v-model="form.endTime" type="datetime-local" required /></label>
      <label>迟到允许时间（分钟）<input v-model.number="form.lateEntryMinutes" type="number" min="0" step="1" required /></label>
      <label>最早交卷时间（分钟）<input v-model.number="form.minSubmitMinutes" type="number" min="0" step="1" required /></label>
    </div>
    <p class="muted">时间按北京时间（Asia/Shanghai）填写。发布后规则与题目将锁定。</p>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <button class="primary" :disabled="busy">{{ busy ? '保存中…' : '保存考试' }}</button>
  </form>
</template>
