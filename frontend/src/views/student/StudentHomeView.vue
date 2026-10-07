<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { studentApi, formatTime } from '../../api/student'
import { lifecycleMeta } from '../../utils/studentExamLifecycle'

const router = useRouter()
const exams = ref([]), loading = ref(true), error = ref(''), success = ref(''), busyId = ref(null)
const groupDefinitions = [
  { key: 'current', title: '现在可以参加', statuses: ['IN_PROGRESS', 'AVAILABLE'] },
  { key: 'upcoming', title: '即将开始', statuses: ['NOT_STARTED'] },
  { key: 'submitted', title: '已交卷，等待成绩', statuses: ['PENDING_REVIEW', 'PENDING_RELEASE'] },
  { key: 'history', title: '历史考试', statuses: ['RESULTS_RELEASED', 'MISSED_ENTRY', 'ABSENT'] },
]
const examGroups = computed(() => groupDefinitions.map(group => ({
  ...group,
  items: exams.value.filter(exam => group.statuses.includes(exam.lifecycleStatus)
    || (group.key === 'history' && !groupDefinitions.some(item => item.statuses.includes(exam.lifecycleStatus))))
    .sort((a, b) => {
      if (group.key === 'current' && a.lifecycleStatus !== b.lifecycleStatus) return a.lifecycleStatus === 'IN_PROGRESS' ? -1 : 1
      return group.key === 'upcoming' || group.key === 'current'
        ? String(a.startTime).localeCompare(String(b.startTime))
        : String(b.startTime).localeCompare(String(a.startTime))
    }),
})).filter(group => group.key === 'current' || group.items.length))

onMounted(async () => {
  try {
    exams.value = (await studentApi.exams()).data
    const submitted = sessionStorage.getItem('studentSubmitSuccess')
    if (submitted) {
      const { examId } = JSON.parse(submitted)
      if (exams.value.some(exam => String(exam.id) === String(examId))) {
        success.value = '交卷成功，答卷已提交。成绩公布后可在“我的成绩”查看。'
        sessionStorage.removeItem('studentSubmitSuccess')
      }
    }
  } catch (e) { error.value = e.message || '加载考试失败，请刷新重试' }
  finally { loading.value = false }
})
async function act(exam) {
  const status = exam.lifecycleStatus
  if (status === 'NOT_STARTED') return router.push('/student/exams/' + exam.id)
  if (status === 'RESULTS_RELEASED') return router.push('/student/results/' + exam.submissionId)
  if (!['AVAILABLE', 'IN_PROGRESS'].includes(status)) return
  busyId.value = exam.id; error.value = ''
  try {
    const paper = (await studentApi.enter(exam.id)).data
    router.push('/student/paper/' + paper.submissionId)
  } catch (e) { error.value = e.message }
  finally { busyId.value = null }
}
</script>

<template>
  <div class="student-home">
    <header class="page-heading"><div><h1>我的考试</h1><p class="muted">参加考试、查看安排与交卷状态</p></div><RouterLink class="secondary" to="/student/results">我的成绩</RouterLink></header>
    <p v-if="success" class="success-message" role="status">{{ success }}</p>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <p v-if="loading" class="loading-state" role="status">正在加载考试…</p>
    <p v-else-if="!error && !exams.length" class="student-empty">当前没有考试安排。考试发布后会显示在这里。</p>
    <template v-else-if="exams.length">
      <section v-for="group in examGroups" :key="group.key" class="student-task-section" :data-priority="group.key" :aria-labelledby="'student-group-' + group.key">
        <header class="section-head"><h2 :id="'student-group-' + group.key">{{ group.title }}</h2><span class="muted">{{ group.items.length }} 场</span></header>
        <p v-if="!group.items.length" class="student-empty">当前没有可进入或正在作答的考试。</p>
        <div v-else class="student-task-list">
          <article v-for="exam in group.items" :key="exam.id" class="student-task-item" :data-exam-id="exam.id">
            <div class="student-task-main">
              <div class="student-task-heading"><h3>{{ exam.title }}</h3><span class="badge" :data-tone="lifecycleMeta(exam.lifecycleStatus).tone">{{ lifecycleMeta(exam.lifecycleStatus).label }}</span></div>
              <p class="student-task-course">{{ exam.courseName }}</p>
              <p class="student-exam-time"><span>开始 {{ formatTime(exam.startTime) }}</span><span>结束 {{ formatTime(exam.endTime) }}</span></p>
              <p v-if="lifecycleMeta(exam.lifecycleStatus).hint" class="student-state-hint">{{ lifecycleMeta(exam.lifecycleStatus).hint }}</p>
              <p v-if="exam.lifecycleStatus === 'RESULTS_RELEASED'" class="student-published-score">最终成绩 <strong>{{ exam.totalScore ?? '—' }}</strong> / {{ exam.maxScore }}</p>
            </div>
            <div v-if="lifecycleMeta(exam.lifecycleStatus).action" class="student-task-action"><button :class="group.key === 'current' ? 'primary' : 'secondary'" :disabled="busyId === exam.id" @click="act(exam)">{{ busyId === exam.id ? '正在进入…' : lifecycleMeta(exam.lifecycleStatus).action }}</button></div>
          </article>
        </div>
      </section>
    </template>
  </div>
</template>
