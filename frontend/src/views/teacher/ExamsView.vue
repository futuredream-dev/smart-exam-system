<script setup>
import { computed, onMounted, ref } from 'vue'
import { loadWorkspace } from '../../api/teacher'
import ExamList from '../../components/ExamList.vue'
const exams = ref([]), error = ref(''), loading = ref(true), filter = ref('')
const filtered = computed(() => exams.value.filter(e => !filter.value || e.status === filter.value))
onMounted(async () => { try { exams.value = (await loadWorkspace()).exams } catch(e) { error.value = e.message } finally { loading.value = false } })
</script>
<template>
  <header class="page-heading"><div><p class="eyebrow">EXAM CENTER</p><h1>考试管理</h1><p class="muted">查看全部课程考试，维护草稿并准备发布。</p></div><RouterLink class="primary link-button" to="/teacher/courses">进入课程创建考试</RouterLink></header>
  <p v-if="error" class="error-message">{{ error }}</p>
  <section class="surface"><div class="section-head"><h2>我的考试</h2><label>筛选状态 <select v-model="filter"><option value="">全部状态</option><option value="DRAFT">草稿</option><option value="PUBLISHED">已发布</option><option value="GRADING">阅卷中</option><option value="RELEASED">已发布成绩</option></select></label></div><p v-if="loading" class="empty-state">正在加载考试…</p><ExamList v-else :exams="filtered" /></section>
</template>
