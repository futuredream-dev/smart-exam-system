<script setup>
import { onMounted, ref, watch } from 'vue'
import { teacherApi, formatTime } from '../api/teacher'
import { gradingStatus, submissionStatus, statusLabel, statusTone } from '../utils/statusDisplay'
import TeacherModal from './TeacherModal.vue'

const props = defineProps({ examId: { required: true }, workspace: Object })
const emit = defineEmits(['released'])
const rows = ref([]), error = ref(''), notice = ref(''), loading = ref(true)
const busy = ref(false), exporting = ref(false), releaseOpen = ref(false)
async function load() {
  loading.value = true; error.value = ''
  try { rows.value = (await teacherApi.gradingSubmissions(props.examId)).data }
  catch (e) { error.value = e.message || '答卷列表加载失败，请重试' }
  finally { loading.value = false }
}
function reviewLink(row) { return '/teacher/exams/' + props.examId + '/grading/submissions/' + row.id }
function responseFileName(value) {
  const encoded = value?.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
  if (encoded) try { return decodeURIComponent(encoded) } catch {}
  return '考试成绩.xlsx'
}
async function exportResults() {
  exporting.value = true; error.value = ''
  try {
    const response = await teacherApi.exportExamResults(props.examId), url = URL.createObjectURL(response.data), link = document.createElement('a')
    link.href = url; link.download = responseFileName(response.headers['content-disposition'])
    document.body.appendChild(link); link.click(); link.remove(); URL.revokeObjectURL(url)
  } catch (e) { error.value = e.message || '导出失败，请稍后重试' }
  finally { exporting.value = false }
}
function askRelease() { error.value = ''; releaseOpen.value = true }
async function release() {
  if (busy.value || !props.workspace?.canReleaseResults) return
  busy.value = true; error.value = ''
  try {
    await teacherApi.publishExamResults(props.examId)
    releaseOpen.value = false; notice.value = '本场成绩已统一发布'
    await load(); emit('released')
  } catch (e) { error.value = e.message || '成绩发布失败，请重试' }
  finally { busy.value = false }
}
onMounted(load); watch(() => props.examId, load)
</script>
<template>
  <p v-if="error && !releaseOpen" class="error-message" role="alert">{{ error }} <button class="ghost" @click="load">重新加载答卷</button></p>
  <p v-if="notice" class="success-message" role="status">{{ notice }}</p>
  <div class="section-head"><div><h2>已提交答卷</h2><p v-if="workspace" class="muted">待阅 {{ workspace.pendingReviewCount }} 份 · 已复核 {{ workspace.reviewedCount }} 份</p></div><div class="actions"><button class="secondary" :disabled="exporting" @click="exportResults">{{ exporting ? '导出中…' : '导出成绩' }}</button><span v-if="workspace?.resultsReleasedAt" class="badge RESULTS_RELEASED">成绩已发布</span><button v-else-if="workspace" class="primary" :disabled="busy || !workspace.canReleaseResults" @click="askRelease">{{ busy ? '发布中…' : '发布成绩' }}</button></div></div>
  <p v-if="workspace && !workspace.resultsReleasedAt && !workspace.canReleaseResults" class="notice">{{ workspace.releaseBlockReason }}</p>
  <p v-if="loading" class="loading-state" role="status">正在加载已提交答卷…</p>
  <p v-else-if="!error && !rows.length" class="empty-state">暂无已提交答卷。</p>
  <div v-else-if="rows.length" class="table-scroll"><table><thead><tr><th>学生</th><th>提交状态</th><th>提交时间</th><th>阅卷状态</th><th>最终成绩</th><th class="table-action">操作</th></tr></thead><tbody><tr v-for="row in rows" :key="row.id"><td>{{ row.studentName }}</td><td><span class="badge" :data-tone="statusTone(submissionStatus, row.status)">{{ statusLabel(submissionStatus, row.status) }}</span></td><td>{{ formatTime(row.submitTime) }}</td><td><span class="badge" :data-tone="statusTone(gradingStatus, row.gradingStatus)">{{ statusLabel(gradingStatus, row.gradingStatus) }}</span></td><td>{{ row.totalScore ?? '—' }}</td><td class="table-action"><RouterLink class="ghost" :to="reviewLink(row)">{{ row.scorePublished ? '查看答卷' : row.pendingManualReview ? '继续阅卷' : '查看答卷' }}</RouterLink></td></tr></tbody></table></div>
  <TeacherModal v-if="releaseOpen" title="确认发布成绩" size="confirm" :busy="busy" @close="releaseOpen = false">
    <div class="confirmation-copy"><p><strong>{{ workspace?.examTitle }}</strong></p><p>发布后学生可以查看正式成绩，本场答卷将不能继续修改评分和评语，且不能通过普通页面撤回。</p><p v-if="error" class="error-message" role="alert">{{ error }}</p></div>
    <template #footer><button class="secondary" :disabled="busy" @click="releaseOpen = false">取消</button><button class="primary" :disabled="busy || !workspace?.canReleaseResults" @click="release">{{ busy ? '发布中…' : '确认发布成绩' }}</button></template>
  </TeacherModal>
</template>
