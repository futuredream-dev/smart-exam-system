<script setup>
import { onMounted, ref } from 'vue'
import { studentApi, formatTime } from '../../api/student'

const rows = ref([]), error = ref(''), loading = ref(true)
onMounted(async () => {
  try { rows.value = (await studentApi.results()).data }
  catch (e) { error.value = e.message || '加载成绩失败，请刷新重试' }
  finally { loading.value = false }
})
</script>

<template>
  <div class="student-results-page">
    <header class="page-heading"><div><h1>我的成绩</h1><p class="muted">查看已公布的最终成绩</p></div><RouterLink class="secondary" to="/student">我的考试</RouterLink></header>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <p v-if="loading" class="loading-state" role="status">正在加载成绩…</p>
    <div v-else-if="!error && !rows.length" class="student-empty"><p>暂时没有已公布的成绩。</p><p>已交卷考试的状态可在“我的考试”查看。</p><RouterLink class="secondary" to="/student">查看我的考试</RouterLink></div>
    <section v-else-if="!error" class="surface student-results-list" aria-label="已公布成绩">
      <article v-for="row in rows" :key="row.submissionId" class="student-result-row">
        <div class="student-result-identity"><h2>{{ row.examTitle }}</h2><p>{{ row.courseName }}</p><small>{{ formatTime(row.publishedAt) }} 公布</small></div>
        <div class="student-result-score"><span>最终成绩</span><strong>{{ row.totalScore ?? '—' }}<small> / {{ row.maxScore }} 分</small></strong></div>
        <RouterLink class="secondary" :to="'/student/results/' + row.submissionId">查看详情</RouterLink>
      </article>
    </section>
  </div>
</template>
