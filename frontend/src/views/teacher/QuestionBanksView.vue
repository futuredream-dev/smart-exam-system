<script setup>
import { computed, onMounted, ref } from 'vue'
import { teacherApi, formatTime } from '../../api/teacher'
import TeacherModal from '../../components/TeacherModal.vue'
const rows = ref([]), loading = ref(true), busy = ref(false), error = ref(''), notice = ref(''), opened = ref(false), editing = ref(null)
const form = ref({ name: '', description: '' })
const myBanks = computed(() => rows.value.filter(row => row.scope === 'TEACHER'))
const platformBanks = computed(() => rows.value.filter(row => row.scope === 'PLATFORM'))
async function load() { loading.value = true; error.value = ''; try { rows.value = (await teacherApi.questionBanks()).data } catch (e) { error.value = e.message } finally { loading.value = false } }
function open(row) { editing.value = row; form.value = { name: row?.name || '', description: row?.description || '' }; error.value = ''; opened.value = true }
async function save() { if (!form.value.name.trim()) { error.value = '题库名称不能为空'; return } busy.value = true; try { await teacherApi.saveQuestionBank(editing.value?.id, form.value); opened.value = false; notice.value = '题库保存成功'; await load() } catch (e) { error.value = e.message } finally { busy.value = false } }
async function archive(row) { if (!window.confirm(`确定归档题库“${row.name}”吗？归档后不能继续编辑题目。`)) return; try { await teacherApi.archiveQuestionBank(row.id); notice.value = '题库已归档'; await load() } catch (e) { error.value = e.message } }
onMounted(load)
</script>
<template>
  <header class="page-heading"><div><h1>题库管理</h1><p class="muted">维护个人题库，并可查看平台公共题库用于组卷。</p></div><button class="primary" @click="open(null)">+ 新建题库</button></header>
  <p v-if="notice" class="success-message" role="status">{{ notice }}</p><p v-if="error && !opened" class="error-message" role="alert">{{ error }}</p>
  <p v-if="loading" class="empty-state">正在加载题库…</p>
  <template v-else>
    <div class="section-head"><h2>我的题库</h2><span class="muted">个人维护，可用于组卷</span></div>
    <p v-if="!myBanks.length" class="empty-state">还没有个人题库，点击“新建题库”开始。</p>
    <div v-else class="course-grid"><article v-for="row in myBanks" :key="row.id" class="surface course-card">
      <div class="teacher-card-heading"><div class="course-icon">题</div><h2>{{ row.name }}</h2></div><p class="muted preserve card-description">{{ row.description || '暂无说明' }}</p>
      <div class="course-meta"><span>{{ row.questionCount }} 道题目</span><small>{{ formatTime(row.updatedAt) }} 更新</small></div>
      <footer class="actions"><RouterLink class="primary link-button" :to="'/teacher/question-banks/' + row.id">进入题库</RouterLink><button class="ghost" @click="open(row)">编辑</button><button class="danger" @click="archive(row)">归档</button></footer>
    </article></div>
    <template v-if="platformBanks.length">
      <div class="section-head"><h2>平台题库</h2><span class="muted">平台公共维护，仅可查看和用于组卷</span></div>
      <div class="course-grid"><article v-for="row in platformBanks" :key="row.id" class="surface course-card">
        <div class="teacher-card-heading"><div class="course-icon">公</div><h2>{{ row.name }}</h2></div><p class="muted preserve card-description">{{ row.description || '暂无说明' }}</p>
        <div class="course-meta"><span>平台公共</span><span>{{ row.questionCount }} 道题目</span><small>{{ formatTime(row.updatedAt) }} 更新</small></div>
        <footer class="actions"><RouterLink class="primary link-button" :to="'/teacher/question-banks/' + row.id">进入题库</RouterLink></footer>
      </article></div>
    </template>
  </template>
  <TeacherModal v-if="opened" :title="editing ? '编辑题库' : '新建题库'" @close="!busy && (opened = false)"><form class="editor-form" @submit.prevent="save">
    <label>题库名称<input v-model="form.name" maxlength="160" required /></label><label>说明<textarea v-model="form.description" maxlength="10000" rows="4" /></label>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p><button class="primary" :disabled="busy">{{ busy ? '保存中…' : '保存题库' }}</button>
  </form></TeacherModal>
</template>
