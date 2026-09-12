<script setup>
import { onMounted, ref } from 'vue'
import { createTeacher, listTeachers, resetTeacherPassword, setTeacherStatus } from '../../api/admin'
const teachers = ref([]), keyword = ref(''), username = ref(''), name = ref('')
const error = ref(''), busy = ref(false), temporary = ref(null)
async function load() { error.value = ''; try { teachers.value = (await listTeachers(keyword.value)).data } catch (e) { error.value = e.message || '加载教师失败' } }
async function create() { error.value = ''; busy.value = true; try { temporary.value = (await createTeacher({ username: username.value, name: name.value })).data; username.value = ''; name.value = ''; await load() } catch (e) { error.value = e.message || '创建教师失败' } finally { busy.value = false } }
async function toggle(teacher) { error.value = ''; try { await setTeacherStatus(teacher.id, teacher.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'); await load() } catch (e) { error.value = e.message || '更新状态失败' } }
async function resetPassword(teacher) { error.value = ''; try { temporary.value = (await resetTeacherPassword(teacher.id)).data } catch (e) { error.value = e.message || '重置密码失败' } }
onMounted(load)
</script>
<template>
  <header class="page-heading"><div><p class="eyebrow">ADMIN CONSOLE</p><h1>教师账号管理</h1><p class="muted">创建教师账号，并管理登录状态与临时密码。</p></div></header>
  <p v-if="error" class="error-message">{{ error }}</p>
  <section class="surface admin-create-panel"><h2>创建教师</h2><form class="form-grid" @submit.prevent="create"><label>登录账号<input v-model.trim="username" required maxlength="64" pattern="[A-Za-z0-9_.-]+"></label><label>教师姓名<input v-model.trim="name" required maxlength="64"></label><button class="primary" :disabled="busy">{{ busy ? '创建中…' : '创建教师' }}</button></form></section>
  <div class="section-head"><h2>教师列表</h2><form class="actions" @submit.prevent="load"><input v-model.trim="keyword" placeholder="按姓名或账号搜索"><button class="secondary">搜索</button></form></div>
  <div class="table-scroll"><table><thead><tr><th>账号</th><th>姓名</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="teacher in teachers" :key="teacher.id"><td>{{ teacher.username }}</td><td>{{ teacher.name }}</td><td><span :class="['badge', teacher.status]">{{ teacher.status === 'ACTIVE' ? '启用' : '停用' }}</span></td><td class="actions"><button :class="teacher.status === 'ACTIVE' ? 'danger' : 'secondary'" @click="toggle(teacher)">{{ teacher.status === 'ACTIVE' ? '停用' : '启用' }}</button><button class="ghost" @click="resetPassword(teacher)">重置密码</button></td></tr><tr v-if="!teachers.length"><td colspan="4" class="muted">暂无教师账号</td></tr></tbody></table></div>
  <div v-if="temporary" class="modal-backdrop" @click.self="temporary = null"><section class="modal-panel temporary-dialog"><h2>临时密码（仅显示一次）</h2><p>{{ temporary.name }}（{{ temporary.username }}）</p><div class="temporary-password">{{ temporary.temporaryPassword }}</div><p class="muted">请立即安全地交给教师。关闭后无法再次查看。</p><button class="primary" @click="temporary = null">我已保存并关闭</button></section></div>
</template>
