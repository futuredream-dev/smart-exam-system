<script setup>
import { onMounted, ref } from 'vue'
import { teacherApi, formatTime } from '../../api/teacher'
import TeacherModal from '../../components/TeacherModal.vue'
const rows = ref([]), loading = ref(true), busy = ref(false), error = ref(''), notice = ref(''), editing = ref(null), opened = ref(false)
const form = ref({ courseName: '', description: '' })
async function load() { loading.value = true; try { rows.value = (await teacherApi.courses()).data } catch(e) { error.value = e.message } finally { loading.value = false } }
function open(course) { editing.value = course; form.value = { courseName: course?.courseName || '', description: course?.description || '' }; error.value = ''; opened.value = true }
async function save() {
  if (!form.value.courseName.trim()) { error.value = '课程名称不能为空'; return }
  busy.value = true
  try { await teacherApi.saveCourse(editing.value?.id, form.value); opened.value = false; notice.value = '课程保存成功'; await load() }
  catch(e) { error.value = e.message } finally { busy.value = false }
}
async function remove(course) {
  if (!window.confirm('确定删除课程“' + course.courseName + '”吗？')) return
  try { await teacherApi.deleteCourse(course.id); notice.value = '课程已删除'; await load() } catch(e) { error.value = e.message }
}
onMounted(load)
</script>
<template>
  <header class="page-heading"><div><h1>课程管理</h1><p class="muted">管理自己的课程，开始准备下一场考试。</p></div><button class="primary" @click="open(null)">+ 创建课程</button></header>
  <p v-if="notice" class="success-message" role="status">{{ notice }}</p>
  <p v-if="error && !opened" class="error-message" role="alert">{{ error }}</p>
  <p v-if="loading" class="empty-state">正在加载课程…</p>
  <div v-else-if="!rows.length" class="empty-state">还没有课程，点击“创建课程”开始。</div>
  <div v-else class="course-grid">
    <article v-for="course in rows" :key="course.id" class="surface course-card">
      <div class="teacher-card-heading"><div class="course-icon">课</div><h2>{{ course.courseName }}</h2></div><p class="muted preserve card-description">{{ course.description || '暂无课程描述' }}</p>
      <div class="course-meta"><span>{{ course.examCount }} 场考试</span><small>{{ formatTime(course.createdAt) }} 创建</small></div>
      <footer class="actions"><RouterLink class="primary link-button" :to="'/teacher/courses/' + course.id">进入课程</RouterLink><button class="ghost" @click="open(course)">编辑</button><button class="danger" @click="remove(course)">删除</button></footer>
    </article>
  </div>
  <TeacherModal v-if="opened" :title="editing ? '编辑课程' : '创建课程'" @close="!busy && (opened = false)">
    <form class="editor-form" @submit.prevent="save">
      <label>课程名称<input v-model="form.courseName" required maxlength="120" /></label>
      <label>课程描述<textarea v-model="form.description" maxlength="10000" rows="4" /></label>
      <p v-if="error" class="error-message" role="alert">{{ error }}</p>
      <button class="primary" :disabled="busy">{{ busy ? '保存中…' : '保存课程' }}</button>
    </form>
  </TeacherModal>
</template>
