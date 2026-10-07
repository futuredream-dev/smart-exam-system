<script setup>
import { computed, onMounted, ref } from 'vue'
import { authState } from '../../auth/auth'
import { teacherApi, formatTime } from '../../api/teacher'

const dashboard = ref({ pendingReview: [], readyToRelease: [], ongoing: [] })
const error = ref('')
const loading = ref(true)
const visiblePending = computed(() => dashboard.value.pendingReview.slice(0, 5))
const visibleReady = computed(() => dashboard.value.readyToRelease.slice(0, 5))
const visibleOngoing = computed(() => dashboard.value.ongoing.slice(0, 5))
const pendingPaperCount = computed(() => dashboard.value.pendingReview.reduce((total, item) => total + item.pendingReviewCount, 0))
const gradingLink = id => `/teacher/exams/${id}?tab=grading`
const overviewLink = id => `/teacher/exams/${id}?tab=overview`

onMounted(async () => {
  try {
    dashboard.value = (await teacherApi.dashboard()).data
  } catch (cause) {
    error.value = cause.message
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="teacher-home">
    <header class="page-heading">
      <div>
        <h1>工作台</h1>
        <p class="muted">{{ authState.currentUser?.name }}，这里是当前需要处理的教学任务。</p>
      </div>
      <RouterLink class="secondary" to="/teacher/exams">查看全部考试</RouterLink>
    </header>

    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <p v-if="loading" class="loading-state">正在加载工作台…</p>
    <template v-else-if="!error">
      <nav class="teacher-home-summary" aria-label="工作台任务概览">
        <a href="#pending-review"><span>待阅卷</span><strong>{{ pendingPaperCount }}</strong><small>份答卷</small></a>
        <a href="#ready-to-release"><span>待发布成绩</span><strong>{{ dashboard.readyToRelease.length }}</strong><small>场考试</small></a>
        <a href="#ongoing-exams"><span>进行中考试</span><strong>{{ dashboard.ongoing.length }}</strong><small>场考试</small></a>
      </nav>

      <section class="teacher-home-work" aria-labelledby="teacher-work-title">
        <h2 id="teacher-work-title">需要处理</h2>

        <section id="pending-review" class="teacher-home-section" aria-labelledby="pending-review-title">
          <header class="teacher-home-section-head">
            <div><h3 id="pending-review-title">待阅卷</h3><span class="muted">按考试结束时间排序</span></div>
            <RouterLink class="ghost" to="/teacher/grading-tasks">查看全部阅卷任务</RouterLink>
          </header>
          <p v-if="!dashboard.pendingReview.length" class="teacher-home-empty">当前没有待阅卷任务</p>
          <div v-else class="teacher-home-table-scroll">
            <table>
              <thead><tr><th>考试名称</th><th>课程</th><th>剩余答卷</th><th>已阅</th><th>操作</th></tr></thead>
              <tbody><tr v-for="item in visiblePending" :key="item.examId">
                <td class="teacher-home-title">{{ item.examTitle }}</td>
                <td>{{ item.courseName }}</td>
                <td><strong class="teacher-home-count">{{ item.pendingReviewCount }} 份</strong></td>
                <td>{{ item.reviewedCount }} 份</td>
                <td><RouterLink class="ghost" :to="gradingLink(item.examId)">继续阅卷</RouterLink></td>
              </tr></tbody>
            </table>
          </div>
          <p v-if="dashboard.pendingReview.length > 5" class="teacher-home-more">首页显示前 5 场，共 {{ dashboard.pendingReview.length }} 场待阅卷考试。</p>
        </section>

        <section id="ready-to-release" class="teacher-home-section" aria-labelledby="ready-to-release-title">
          <header class="teacher-home-section-head">
            <div><h3 id="ready-to-release-title">待发布成绩</h3><span class="muted">阅卷完成后核对并发布</span></div>
            <RouterLink class="ghost" to="/teacher/exams">查看全部考试</RouterLink>
          </header>
          <p v-if="!dashboard.readyToRelease.length" class="teacher-home-empty">当前没有待发布成绩</p>
          <div v-else class="teacher-home-table-scroll">
            <table>
              <thead><tr><th>考试名称</th><th>课程</th><th>已阅答卷</th><th>操作</th></tr></thead>
              <tbody><tr v-for="item in visibleReady" :key="item.examId">
                <td class="teacher-home-title">{{ item.examTitle }}</td>
                <td>{{ item.courseName }}</td>
                <td>{{ item.reviewedCount }} 份</td>
                <td><RouterLink class="ghost" :to="gradingLink(item.examId)">核对并发布</RouterLink></td>
              </tr></tbody>
            </table>
          </div>
          <p v-if="dashboard.readyToRelease.length > 5" class="teacher-home-more">首页显示前 5 场，共 {{ dashboard.readyToRelease.length }} 场待发布成绩考试。</p>
        </section>
      </section>

      <section id="ongoing-exams" class="teacher-home-work" aria-labelledby="ongoing-title">
        <h2 id="ongoing-title">正在进行</h2>
        <div class="teacher-home-section">
          <p v-if="!dashboard.ongoing.length" class="teacher-home-empty">当前没有正在进行的考试</p>
          <div v-else class="teacher-home-table-scroll">
            <table>
              <thead><tr><th>考试名称</th><th>课程</th><th>已交 / 应考</th><th>结束时间</th><th>操作</th></tr></thead>
              <tbody><tr v-for="item in visibleOngoing" :key="item.examId">
                <td class="teacher-home-title">{{ item.examTitle }}</td>
                <td>{{ item.courseName }}</td>
                <td>{{ item.submittedCount }} / {{ item.candidateCount }}</td>
                <td>{{ formatTime(item.endTime) }}</td>
                <td><RouterLink class="ghost" :to="overviewLink(item.examId)">查看考试</RouterLink></td>
              </tr></tbody>
            </table>
          </div>
          <p v-if="dashboard.ongoing.length > 5" class="teacher-home-more">首页显示前 5 场，共 {{ dashboard.ongoing.length }} 场进行中考试。</p>
        </div>
      </section>
    </template>
  </div>
</template>
