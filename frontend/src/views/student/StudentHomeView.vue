<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { studentApi, formatTime } from '../../api/student'
import { lifecycleMeta } from '../../utils/studentExamLifecycle'

const router = useRouter()
const exams = ref([])
const loading = ref(true)
const error = ref('')
const success = ref('')
const busyId = ref(null)

onMounted(async () => {
  try {
    exams.value = (await studentApi.exams()).data
    const submitted = sessionStorage.getItem('studentSubmitSuccess')
    if (submitted) {
      const { examId } = JSON.parse(submitted)
      if (exams.value.some(exam => String(exam.id) === String(examId))) {
        success.value = '交卷成功，成绩将在教师批阅并统一发布后显示。'
        sessionStorage.removeItem('studentSubmitSuccess')
      }
    }
  } catch (e) { error.value = e.message } finally { loading.value = false }
})

async function act(exam) {
  const status = exam.lifecycleStatus
  if (status === 'NOT_STARTED') return router.push(`/student/exams/${exam.id}`)
  if (status === 'RESULTS_RELEASED') return router.push(`/student/results/${exam.submissionId}`)
  if (!['AVAILABLE', 'IN_PROGRESS'].includes(status)) return
  busyId.value = exam.id
  error.value = ''
  try {
    const paper = (await studentApi.enter(exam.id)).data
    router.push(`/student/paper/${paper.submissionId}`)
  } catch (e) { error.value = e.message } finally { busyId.value = null }
}
</script>

<template>
  <header class="page-heading"><div><h1>我的考试</h1><p class="muted">查看考试当前进度与下一步操作。</p></div><RouterLink class="secondary" to="/student/results">查看我的成绩</RouterLink></header>
  <p v-if="success" class="success-message">{{ success }}</p>
  <p v-if="error" class="error-message">{{ error }}</p>
  <p v-if="loading" class="empty-state">正在加载考试…</p>
  <p v-else-if="!exams.length" class="empty-state">当前没有已发布的考试。</p>
  <div v-else class="course-grid">
    <article v-for="exam in exams" :key="exam.id" class="surface course-card student-exam-card">
      <div class="student-card-heading"><span :class="['badge', exam.lifecycleStatus]">{{ lifecycleMeta(exam.lifecycleStatus).label }}</span><h3>{{ exam.title }}</h3></div><p>{{ exam.courseName }}</p>
      <small class="muted student-exam-time">{{ formatTime(exam.startTime) }} 至 {{ formatTime(exam.endTime) }}</small>
      <p v-if="lifecycleMeta(exam.lifecycleStatus).hint" class="student-state-hint">{{ lifecycleMeta(exam.lifecycleStatus).hint }}</p>
      <p v-if="exam.lifecycleStatus === 'RESULTS_RELEASED'" class="student-score">最终成绩：{{ exam.totalScore }} / {{ exam.maxScore }}</p>
      <button v-if="lifecycleMeta(exam.lifecycleStatus).action" :class="[exam.lifecycleStatus === 'NOT_STARTED' ? 'secondary' : 'primary','student-primary-action']" :disabled="busyId === exam.id" @click="act(exam)">{{ busyId === exam.id ? '正在进入…' : lifecycleMeta(exam.lifecycleStatus).action }}</button>
    </article>
  </div>
</template>
