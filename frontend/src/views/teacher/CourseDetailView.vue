<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { teacherApi, formatTime } from '../../api/teacher'
import ExamList from '../../components/ExamList.vue'
import ExamForm from '../../components/ExamForm.vue'
import TeacherModal from '../../components/TeacherModal.vue'
const route = useRoute(), router = useRouter()
const course = ref(null), exams = ref([]), error = ref(''), loading = ref(true), opened = ref(false)
async function load() {
  try { const [c,e] = await Promise.all([teacherApi.course(route.params.courseId), teacherApi.exams(route.params.courseId)]); course.value = c.data; exams.value = e.data }
  catch(e) { error.value = e.message } finally { loading.value = false }
}
onMounted(load)
</script>
<template>
  <RouterLink to="/teacher/courses">← 返回课程管理</RouterLink>
  <p v-if="error" class="error-message">{{ error }}</p><p v-if="loading" class="empty-state">正在加载课程…</p>
  <template v-if="course">
    <header class="page-heading"><div><p class="eyebrow">COURSE OVERVIEW</p><h1>{{ course.courseName }}</h1><p class="muted preserve">{{ course.description || '暂无课程描述' }}</p></div><button class="primary" @click="opened = true">+ 创建考试</button></header>
    <div class="surface meta-strip"><span>授课教师：{{ course.teacherName }}</span><span>创建时间：{{ formatTime(course.createdAt) }}</span><span>{{ exams.length }} 场考试</span></div>
    <section class="surface"><h2>课程考试</h2><ExamList :exams="exams" /></section>
    <TeacherModal v-if="opened" title="创建考试" @close="opened = false"><ExamForm :course-id="course.id" @saved="e => router.push('/teacher/exams/' + e.id)" /></TeacherModal>
  </template>
</template>
