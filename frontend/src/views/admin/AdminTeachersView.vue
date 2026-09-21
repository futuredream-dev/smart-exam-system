<script setup>
import { onMounted, ref } from 'vue'
import { createTeacher, listTeachers, resetTeacherPassword, setTeacherStatus } from '../../api/admin'
import TeacherModal from '../../components/TeacherModal.vue'

const teachers = ref([]), keyword = ref(''), username = ref(''), name = ref('')
const error = ref(''), notice = ref(''), loading = ref(true), searching = ref(false), creating = ref(false)
const rowBusyId = ref(null), temporary = ref(null), confirmation = ref(null)

async function load({ initial = false, search = false } = {}) {
  error.value = ''
  if (initial) loading.value = true
  if (search) searching.value = true
  try { teachers.value = (await listTeachers(keyword.value)).data }
  catch (e) { error.value = e.message || '加载教师失败' }
  finally { loading.value = false; searching.value = false }
}

async function search() { notice.value = ''; await load({ search: true }) }

async function create() {
  error.value = ''; notice.value = ''; creating.value = true
  try {
    temporary.value = (await createTeacher({ username: username.value, name: name.value })).data
    username.value = ''; name.value = ''
    await load()
    notice.value = '教师账号已创建'
  } catch (e) { error.value = e.message || '创建教师失败' }
  finally { creating.value = false }
}

function toggle(teacher) {
  if (teacher.status === 'ACTIVE') confirmation.value = { type: 'disable', teacher }
  else updateStatus(teacher, 'ACTIVE')
}

function askReset(teacher) { confirmation.value = { type: 'reset', teacher } }

async function updateStatus(teacher, status) {
  error.value = ''; notice.value = ''; rowBusyId.value = teacher.id
  try {
    await setTeacherStatus(teacher.id, status)
    await load()
    notice.value = status === 'ACTIVE' ? '教师账号已启用' : '教师账号已停用'
    confirmation.value = null
  } catch (e) { error.value = e.message || '更新状态失败' }
  finally { rowBusyId.value = null }
}

async function confirmAction() {
  const action = confirmation.value
  if (!action) return
  if (action.type === 'disable') { await updateStatus(action.teacher, 'DISABLED'); return }
  error.value = ''; notice.value = ''; rowBusyId.value = action.teacher.id
  try {
    temporary.value = (await resetTeacherPassword(action.teacher.id)).data
    notice.value = '教师密码已重置'
    confirmation.value = null
  } catch (e) { error.value = e.message || '重置密码失败' }
  finally { rowBusyId.value = null }
}

onMounted(() => load({ initial: true }))
</script>

<template>
  <div class="admin-workspace">
    <header class="page-heading"><div><h1>教师账号管理</h1><p class="muted">创建教师账号，并管理登录状态与临时密码。</p></div></header>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <p v-if="notice" class="success-message" role="status">{{ notice }}</p>

    <section class="surface admin-create-panel">
      <div><h2>创建教师</h2><p class="muted">系统将生成仅显示一次的临时密码，教师首次登录后必须修改。</p></div>
      <form class="form-grid" @submit.prevent="create">
        <label>登录账号<input v-model.trim="username" required maxlength="64" pattern="[A-Za-z0-9_.-]+"></label>
        <label>教师姓名<input v-model.trim="name" required maxlength="64"></label>
        <button class="primary" :disabled="creating">{{ creating ? '创建中…' : '创建教师' }}</button>
      </form>
    </section>

    <section class="admin-list-section">
      <div class="section-head admin-toolbar"><div><h2>教师列表</h2><p class="muted">查看账号状态，或按姓名、账号快速查找。</p></div><form class="actions admin-search" @submit.prevent="search"><input v-model.trim="keyword" aria-label="搜索教师" placeholder="按姓名或账号搜索"><button class="secondary" :disabled="searching">{{ searching ? '搜索中…' : '搜索' }}</button></form></div>
      <p v-if="loading" class="empty-state">正在加载教师账号…</p>
      <p v-else-if="!teachers.length" class="empty-state">{{ keyword ? '没有匹配的教师账号' : '暂无教师账号' }}</p>
      <div v-else class="table-scroll"><table><thead><tr><th>账号</th><th>姓名</th><th>状态</th><th class="table-action">操作</th></tr></thead><tbody><tr v-for="teacher in teachers" :key="teacher.id"><td>{{ teacher.username }}</td><td>{{ teacher.name }}</td><td><span :class="['badge', teacher.status]">{{ teacher.status === 'ACTIVE' ? '启用' : '停用' }}</span></td><td class="table-action"><div class="actions admin-table-actions"><button :class="teacher.status === 'ACTIVE' ? 'danger' : 'secondary'" :disabled="rowBusyId === teacher.id" @click="toggle(teacher)">{{ rowBusyId === teacher.id ? '处理中…' : teacher.status === 'ACTIVE' ? '停用' : '启用' }}</button><button class="ghost warning" :disabled="rowBusyId === teacher.id" @click="askReset(teacher)">重置密码</button></div></td></tr></tbody></table></div>
    </section>
  </div>

  <TeacherModal v-if="confirmation" :title="confirmation.type === 'disable' ? '确认停用教师账号' : '确认重置教师密码'" @close="rowBusyId === null && (confirmation = null)">
    <div class="confirmation-copy">
      <p><strong>{{ confirmation.teacher.name }}</strong>（{{ confirmation.teacher.username }}）</p>
      <p v-if="confirmation.type === 'disable'">停用后，该教师将无法登录，已有登录凭据也会失效。</p>
      <p v-else>重置后旧密码立即失效。系统将生成新的临时密码，教师首次登录后必须修改密码。</p>
      <div class="actions"><button class="secondary" :disabled="rowBusyId !== null" @click="confirmation = null">取消</button><button :class="confirmation.type === 'disable' ? 'danger' : 'warning'" :disabled="rowBusyId !== null" @click="confirmAction">{{ rowBusyId !== null ? '处理中…' : confirmation.type === 'disable' ? '确认停用' : '确认重置' }}</button></div>
    </div>
  </TeacherModal>

  <TeacherModal v-if="temporary" title="临时密码（仅显示一次）" @close="temporary = null">
    <div class="temporary-dialog"><p>{{ temporary.name }}（{{ temporary.username }}）</p><div class="temporary-password">{{ temporary.temporaryPassword }}</div><p class="muted">请立即安全地交给教师。关闭后无法再次查看。</p><button class="primary" @click="temporary = null">我已保存并关闭</button></div>
  </TeacherModal>
</template>
