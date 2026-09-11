<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { teacherApi, formatTime } from '../../api/teacher'
import ExamList from '../../components/ExamList.vue'
import ExamForm from '../../components/ExamForm.vue'
import TeacherModal from '../../components/TeacherModal.vue'
const route = useRoute(), router = useRouter()
const course = ref(null), exams = ref([]), enrolledStudents = ref([]), allStudents = ref([])
const error = ref(''), notice = ref(''), loading = ref(true), opened = ref(false), busy = ref(false), selectedStudentId = ref('')
const availableStudents = computed(() => allStudents.value.filter(student => !enrolledStudents.value.some(row => row.id === student.id)))
async function load() {
  try { const [c,e,s,a] = await Promise.all([teacherApi.course(route.params.courseId), teacherApi.exams(route.params.courseId), teacherApi.courseStudents(route.params.courseId), teacherApi.students()]); course.value = c.data; exams.value = e.data; enrolledStudents.value = s.data; allStudents.value = a.data }
  catch(e) { error.value = e.message } finally { loading.value = false }
}
async function addStudent() {
  if (!selectedStudentId.value) return
  busy.value = true; error.value = ''; notice.value = ''
  try { await teacherApi.addCourseStudent(course.value.id, selectedStudentId.value); selectedStudentId.value = ''; notice.value = '学生已加入课程'; await load() }
  catch(e) { error.value = e.message } finally { busy.value = false }
}
async function removeStudent(student) {
  if (!window.confirm(`确定将“${student.name}”移出课程吗？`)) return
  busy.value = true; error.value = ''; notice.value = ''
  try { await teacherApi.removeCourseStudent(course.value.id, student.id); notice.value = '学生已移出课程'; await load() }
  catch(e) { error.value = e.message } finally { busy.value = false }
}
onMounted(load)
</script>
<template>
  <RouterLink to="/teacher/courses">← 返回课程管理</RouterLink>
  <p v-if="error" class="error-message">{{ error }}</p><p v-if="notice" class="success-message">{{ notice }}</p><p v-if="loading" class="empty-state">正在加载课程…</p>
  <template v-if="course">
    <header class="page-heading"><div><p class="eyebrow">COURSE OVERVIEW</p><h1>{{ course.courseName }}</h1><p class="muted preserve">{{ course.description || '暂无课程描述' }}</p></div><button class="primary" @click="opened = true">+ 创建考试</button></header>
    <div class="surface meta-strip"><span>授课教师：{{ course.teacherName }}</span><span>创建时间：{{ formatTime(course.createdAt) }}</span><span>{{ exams.length }} 场考试</span></div>
    <section class="surface">
      <div class="section-head"><h2>学生管理 <span class="muted">已加入 {{ enrolledStudents.length }} 人</span></h2><div class="actions"><select v-model.number="selectedStudentId" :disabled="busy || !availableStudents.length"><option value="">{{ availableStudents.length ? '选择系统学生' : '暂无可添加学生' }}</option><option v-for="student in availableStudents" :key="student.id" :value="student.id">{{ student.name }}（{{ student.username }}）</option></select><button class="primary" :disabled="busy || !selectedStudentId" @click="addStudent">添加学生</button></div></div>
      <p v-if="!enrolledStudents.length" class="empty-state">当前课程还没有学生。</p>
      <div v-else class="table-scroll"><table><thead><tr><th>姓名</th><th>账号</th><th>操作</th></tr></thead><tbody><tr v-for="student in enrolledStudents" :key="student.id"><td>{{ student.name }}</td><td>{{ student.username }}</td><td><button class="danger" :disabled="busy" @click="removeStudent(student)">移出课程</button></td></tr></tbody></table></div>
    </section>
    <section class="surface"><h2>课程考试</h2><ExamList :exams="exams" /></section>
    <TeacherModal v-if="opened" title="创建考试" @close="opened = false"><ExamForm :course-id="course.id" @saved="e => router.push('/teacher/exams/' + e.id)" /></TeacherModal>
  </template>
</template>
