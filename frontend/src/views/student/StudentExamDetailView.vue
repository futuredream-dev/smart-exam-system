<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { studentApi, formatTime } from '../../api/student'
import { lifecycleMeta } from '../../utils/studentExamLifecycle'

const route = useRoute(), router = useRouter()
const exam = ref(), error = ref(''), busy = ref(false), loading = ref(true)
onMounted(async () => {
  try { exam.value = (await studentApi.exam(route.params.examId)).data }
  catch (e) { error.value = e.message || '加载考试详情失败，请刷新重试' }
  finally { loading.value = false }
})
async function enter() {
  busy.value = true; error.value = ''
  try {
    const paper = (await studentApi.enter(exam.value.id)).data
    router.push('/student/paper/' + paper.submissionId)
  } catch (e) { error.value = e.message }
  finally { busy.value = false }
}
</script>

<template>
  <div class="student-detail-page">
    <RouterLink class="student-back-link" to="/student">返回我的考试</RouterLink>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <p v-if="loading" class="loading-state" role="status">正在加载考试详情…</p>
    <article v-if="exam" class="surface student-exam-detail">
      <header class="student-detail-heading"><div><h1>{{ exam.title }}</h1><p class="muted">{{ exam.courseName }}</p></div><span class="badge" :data-tone="lifecycleMeta(exam.lifecycleStatus).tone">{{ lifecycleMeta(exam.lifecycleStatus).label }}</span></header>
      <section class="student-detail-section" aria-labelledby="student-arrangement-title">
        <h2 id="student-arrangement-title">考试安排</h2>
        <dl class="student-exam-facts">
          <div><dt>开始时间</dt><dd>{{ formatTime(exam.startTime) }}</dd></div>
          <div><dt>结束时间</dt><dd>{{ formatTime(exam.endTime) }}</dd></div>
          <div><dt>允许迟到</dt><dd>开考后 {{ exam.lateEntryMinutes }} 分钟内首次入场</dd></div>
          <div><dt>最早交卷</dt><dd>开考 {{ exam.minSubmitMinutes }} 分钟后</dd></div>
          <div><dt>题目数量</dt><dd>{{ exam.questionCount }} 题</dd></div>
          <div v-if="exam.maxScore != null"><dt>试卷总分</dt><dd>{{ exam.maxScore }} 分</dd></div>
        </dl>
      </section>
      <section class="student-detail-section" aria-labelledby="student-instructions-title"><h2 id="student-instructions-title">考试说明</h2><p class="preserve">{{ exam.description || '本场考试暂无补充说明。' }}</p><p class="student-detail-note">超过允许迟到时间后无法首次入场；已进入且仍在答题中的答卷可以继续考试。</p></section>
      <footer class="student-detail-next">
        <p v-if="lifecycleMeta(exam.lifecycleStatus).hint" class="student-state-hint">{{ lifecycleMeta(exam.lifecycleStatus).hint }}</p>
        <p v-else-if="exam.lifecycleStatus === 'NOT_STARTED'" class="muted">考试尚未开始，请按安排时间参加。</p>
        <p v-else-if="exam.lifecycleStatus === 'AVAILABLE'" class="muted">现在可以进入考试。</p>
        <p v-else-if="exam.lifecycleStatus === 'IN_PROGRESS'" class="muted">答卷正在进行中，可以继续考试。</p>
        <button v-if="['AVAILABLE', 'IN_PROGRESS'].includes(exam.lifecycleStatus)" class="primary" :disabled="busy" @click="enter">{{ busy ? '正在进入…' : lifecycleMeta(exam.lifecycleStatus).action }}</button>
        <RouterLink v-else-if="exam.lifecycleStatus === 'RESULTS_RELEASED'" class="secondary" :to="'/student/results/' + exam.submissionId">查看成绩</RouterLink>
      </footer>
    </article>
  </div>
</template>
