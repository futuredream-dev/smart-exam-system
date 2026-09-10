<script setup>
import { computed, onMounted, ref } from 'vue'
import { authState } from '../../auth/auth'
import { loadWorkspace } from '../../api/teacher'
import ExamList from '../../components/ExamList.vue'
const courses = ref([]), exams = ref([]), error = ref(''), loading = ref(true)
const stats = computed(() => [['我的课程', courses.value.length], ['全部考试', exams.value.length], ['草稿考试', exams.value.filter(e => e.status === 'DRAFT').length], ['已发布考试', exams.value.filter(e => e.status === 'PUBLISHED').length]])
onMounted(async () => {
  try { const result = await loadWorkspace(); courses.value = result.courses; exams.value = result.exams.sort((a,b) => b.id - a.id) }
  catch(e) { error.value = e.message } finally { loading.value = false }
})
</script>
<template>
  <header class="page-heading"><div><p class="eyebrow">TEACHER WORKSPACE</p><h1>教师工作台</h1><p class="muted">{{ authState.currentUser?.name }}，欢迎回来。管理课程，准备下一场考试。</p></div><RouterLink class="primary" to="/teacher/courses">管理我的课程 →</RouterLink></header>
  <p v-if="error" class="error-message" role="alert">{{ error }}</p><p v-if="loading" class="empty-state">正在加载工作台…</p>
  <template v-else>
    <div class="stats-grid"><article v-for="[label, count] in stats" :key="label" class="stat-card"><span>{{ label }}</span><strong>{{ count }}</strong><small>当前账号的数据</small></article></div>
    <div class="section-head"><h2>我的课程</h2><RouterLink to="/teacher/courses">查看全部 →</RouterLink></div>
    <p v-if="!courses.length" class="empty-state">还没有课程，先创建第一门课程吧。</p>
    <div class="course-grid"><RouterLink v-for="course in courses.slice(0, 3)" :key="course.id" :to="'/teacher/courses/' + course.id" class="surface course-card"><span class="course-icon">课</span><h3>{{ course.courseName }}</h3><p class="muted">{{ course.description || '暂无课程说明' }}</p><span>{{ course.examCount }} 场考试 →</span></RouterLink></div>
    <div class="section-head"><h2>最近考试</h2><RouterLink to="/teacher/exams">查看全部 →</RouterLink></div><ExamList :exams="exams.slice(0, 5)" />
  </template>
</template>
