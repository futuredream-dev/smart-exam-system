<script setup>
import { onMounted, reactive, ref } from 'vue'
import { teacherApi } from '../api/teacher'

const props = defineProps({ exam: { type: Object, required: true } })
const emit = defineEmits(['created'])
const candidates = ref([]), chosen = ref([]), busy = ref(false), error = ref(''), loading = ref(true)
const form = reactive({ title: `${props.exam.title}（补考）`, description: props.exam.description || '',
  startTime: '', endTime: '', lateEntryMinutes: 0, minSubmitMinutes: 0 })
onMounted(async () => {
  try { candidates.value = (await teacherApi.examCandidates(props.exam.id)).data }
  catch (e) { error.value = e.message }
  finally { loading.value = false }
})
async function save() {
  error.value = ''
  const duration = (new Date(form.endTime) - new Date(form.startTime)) / 60000
  if (!chosen.value.length) { error.value = '至少选择一名补考学生'; return }
  if (!form.title.trim() || !Number.isFinite(duration) || duration <= 0) { error.value = '请填写名称与新的考试时间'; return }
  if ([form.lateEntryMinutes, form.minSubmitMinutes].some(x => !Number.isInteger(x) || x < 0 || x > duration)) {
    error.value = '规则分钟数必须为非负整数且不能超过考试总时长'; return
  }
  busy.value = true
  try {
    const result = await teacherApi.createMakeup(props.exam.id, {
      ...form, startTime: form.startTime.length === 16 ? form.startTime + ':00' : form.startTime,
      endTime: form.endTime.length === 16 ? form.endTime + ':00' : form.endTime,
      studentIds: chosen.value,
    })
    emit('created', result.data)
  } catch (e) { error.value = e.message }
  finally { busy.value = false }
}
</script>

<template>
  <form class="editor-form" @submit.prevent="save">
    <p class="muted">将复制当前试卷和评分点，创建同课程的补考草稿。可在草稿中继续调整题目。</p>
    <fieldset class="makeup-candidate-fieldset"><legend>选择补考学生</legend><p v-if="loading" class="muted">正在加载原考试名单…</p>
    <div v-else class="makeup-candidate-list"><label v-for="student in candidates" :key="student.studentId"><input v-model="chosen" type="checkbox" :value="student.studentId" /> {{ student.studentName }}（{{ student.username }}） · {{ student.candidateStatus === 'ABSENT' ? '缺考' : student.candidateStatus === 'REVIEWED' ? '已阅' : '原考试考生' }}</label></div></fieldset>
    <label>补考名称<input v-model="form.title" required maxlength="160" /></label>
    <label>考试说明<textarea v-model="form.description" rows="2" maxlength="10000" /></label>
    <div class="form-grid">
      <label>开始时间<input v-model="form.startTime" type="datetime-local" required /></label>
      <label>结束时间<input v-model="form.endTime" type="datetime-local" required /></label>
      <label>迟到允许时间（分钟）<input v-model.number="form.lateEntryMinutes" type="number" min="0" step="1" required /></label>
      <label>最早交卷时间（分钟）<input v-model.number="form.minSubmitMinutes" type="number" min="0" step="1" required /></label>
    </div>
    <p class="muted">时间按北京时间（Asia/Shanghai）填写。</p>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <button class="primary" :disabled="busy || loading">{{ busy ? '创建中…' : '创建补考草稿' }}</button>
  </form>
</template>
