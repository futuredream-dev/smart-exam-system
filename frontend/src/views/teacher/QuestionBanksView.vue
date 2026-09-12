<script setup>
import { onMounted, ref } from 'vue'
import { teacherApi, formatTime } from '../../api/teacher'
import TeacherModal from '../../components/TeacherModal.vue'
const rows = ref([]), loading = ref(true), busy = ref(false), error = ref(''), notice = ref(''), opened = ref(false), editing = ref(null)
const form = ref({ name: '', description: '' })
async function load() { loading.value = true; error.value = ''; try { rows.value = (await teacherApi.questionBanks()).data } catch (e) { error.value = e.message } finally { loading.value = false } }
function open(row) { editing.value = row; form.value = { name: row?.name || '', description: row?.description || '' }; error.value = ''; opened.value = true }
async function save() { if (!form.value.name.trim()) { error.value = '题库名称不能为空'; return } busy.value = true; try { await teacherApi.saveQuestionBank(editing.value?.id, form.value); opened.value = false; notice.value = '题库保存成功'; await load() } catch (e) { error.value = e.message } finally { busy.value = false } }
async function archive(row) { if (!window.confirm(`确定归档题库“${row.name}”吗？归档后不能继续编辑题目。`)) return; try { await teacherApi.archiveQuestionBank(row.id); notice.value = '题库已归档'; await load() } catch (e) { error.value = e.message } }
onMounted(load)
</script>
<template>
  <header class="page-heading"><div><p class="eyebrow">MY QUESTION BANKS</p><h1>题库管理</h1><p class="muted">维护独立的个人题库，为后续组卷准备可用题目。</p></div><button class="primary" @click="open(null)">+ 新建题库</button></header>
  <p v-if="notice" class="success-message" role="status">{{ notice }}</p><p v-if="error && !opened" class="error-message" role="alert">{{ error }}</p>
  <p v-if="loading" class="empty-state">正在加载题库…</p><div v-else-if="!rows.length" class="empty-state">还没有题库，点击“新建题库”开始。</div>
  <div v-else class="course-grid"><article v-for="row in rows" :key="row.id" class="surface course-card">
    <div class="course-icon">题</div><h2>{{ row.name }}</h2><p class="muted preserve">{{ row.description || '暂无说明' }}</p>
    <div class="course-meta"><span>{{ row.questionCount }} 道题目</span><small>{{ formatTime(row.updatedAt) }} 更新</small></div>
    <footer class="actions"><RouterLink class="primary link-button" :to="'/teacher/question-banks/' + row.id">进入题库</RouterLink><button class="ghost" @click="open(row)">编辑</button><button class="danger" @click="archive(row)">归档</button></footer>
  </article></div>
  <TeacherModal v-if="opened" :title="editing ? '编辑题库' : '新建题库'" @close="!busy && (opened = false)"><form class="editor-form" @submit.prevent="save">
    <label>题库名称<input v-model="form.name" maxlength="160" required /></label><label>说明<textarea v-model="form.description" maxlength="10000" rows="4" /></label>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p><button class="primary" :disabled="busy">{{ busy ? '保存中…' : '保存题库' }}</button>
  </form></TeacherModal>
</template>
