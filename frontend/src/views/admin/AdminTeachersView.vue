<script setup>
import { computed, onMounted, ref } from 'vue'
import { createTeacher, listTeachers, resetTeacherPassword, setTeacherStatus } from '../../api/admin'
import TeacherModal from '../../components/TeacherModal.vue'
import { accountStatus, statusLabel, statusTone } from '../../utils/statusDisplay'

const teachers = ref([]), keyword = ref(''), status = ref(''), page = ref(1)
const username = ref(''), name = ref(''), createOpen = ref(false)
const error = ref(''), notice = ref(''), loading = ref(true), creating = ref(false)
const rowBusyId = ref(null), temporary = ref(null), confirmation = ref(null)
const size = 20
const filteredTeachers = computed(() => teachers.value.filter(row => !status.value || row.status === status.value))
const pageCount = computed(() => Math.max(1, Math.ceil(filteredTeachers.value.length / size)))
const visibleTeachers = computed(() => filteredTeachers.value.slice((page.value - 1) * size, page.value * size))
const confirmationTitle = computed(() => ({ enable: '确认启用教师账号', disable: '确认停用教师账号', reset: '确认重置教师密码' })[confirmation.value?.type])

async function load() {
  error.value = ''; loading.value = true
  try {
    teachers.value = (await listTeachers(keyword.value)).data
    page.value = Math.min(page.value, pageCount.value)
  }
  catch (e) { error.value = e.message || '加载教师账号失败，请重试' }
  finally { loading.value = false }
}
function search() { page.value = 1; notice.value = ''; load() }
function clearFilters() { keyword.value = ''; status.value = ''; search() }
function closeCreate() { createOpen.value = false; username.value = ''; name.value = ''; error.value = '' }
function ask(type, teacher) { error.value = ''; confirmation.value = { type, teacher } }
function closeConfirmation() { confirmation.value = null; error.value = '' }

async function create() {
  error.value = ''; notice.value = ''; creating.value = true
  try {
    temporary.value = (await createTeacher({ username: username.value, name: name.value })).data
    closeCreate()
    await load()
    notice.value = '教师账号已创建，临时密码仅显示一次'
  } catch (e) { error.value = e.message || '创建教师失败' }
  finally { creating.value = false }
}
async function confirmAction() {
  const action = confirmation.value
  if (!action) return
  error.value = ''; notice.value = ''; rowBusyId.value = action.teacher.id
  try {
    if (action.type === 'reset') {
      temporary.value = (await resetTeacherPassword(action.teacher.id)).data
      notice.value = '教师密码已重置，临时密码仅显示一次'
    } else {
      await setTeacherStatus(action.teacher.id, action.type === 'enable' ? 'ACTIVE' : 'DISABLED')
      await load()
      notice.value = action.type === 'enable' ? '教师账号已启用' : '教师账号已停用'
    }
    confirmation.value = null
  } catch (e) { error.value = e.message || '操作失败，请重试' }
  finally { rowBusyId.value = null }
}
onMounted(load)
</script>

<template>
  <div class="admin-workspace">
    <header class="page-heading"><div><h1>教师管理</h1><p class="muted">管理平台教师账号、登录状态与临时密码</p></div><button class="primary" @click="error = ''; createOpen = true">创建教师</button></header>
    <p v-if="error && !createOpen && !confirmation" class="error-message" role="alert">{{ error }}</p>
    <p v-if="notice" class="success-message" role="status">{{ notice }}</p>
    <section class="surface admin-list-section" aria-labelledby="admin-teachers-title">
      <form class="admin-filter-row" @submit.prevent="search">
        <label class="admin-filter-main">教师账号 / 姓名<input v-model.trim="keyword" placeholder="按账号或姓名搜索"></label>
        <label>账号状态<select v-model="status" aria-label="账号状态" @change="page = 1"><option value="">全部状态</option><option v-for="(item, key) in accountStatus" :key="key" :value="key">{{ item.label }}</option></select></label>
        <div class="actions admin-filter-actions"><button class="secondary" :disabled="loading">{{ loading ? '查询中…' : '查询' }}</button><button type="button" class="ghost" :disabled="loading" @click="clearFilters">重置筛选</button></div>
      </form>
      <header class="section-head admin-list-head"><h2 id="admin-teachers-title">教师列表</h2><span class="muted">共 {{ filteredTeachers.length }} 个账号</span></header>
      <p v-if="loading" class="loading-state" role="status">正在加载教师账号…</p>
      <p v-else-if="error" class="admin-table-message">教师列表加载失败，请重新查询。</p>
      <p v-else-if="!filteredTeachers.length" class="admin-table-message">{{ keyword || status ? '没有符合筛选条件的教师，请调整筛选条件。' : '暂无教师账号，可通过“创建教师”添加。' }}</p>
      <div v-else class="table-scroll"><table class="admin-account-table"><thead><tr><th scope="col">教师账号</th><th scope="col">姓名</th><th scope="col">账号状态</th><th scope="col" class="table-action">操作</th></tr></thead><tbody>
        <tr v-for="teacher in visibleTeachers" :key="teacher.id"><td>{{ teacher.username }}</td><td>{{ teacher.name }}</td><td><span class="badge" :data-tone="statusTone(accountStatus, teacher.status)">{{ statusLabel(accountStatus, teacher.status) }}</span></td><td class="table-action"><div class="actions admin-table-actions"><button :class="['ghost', { 'admin-danger-link': teacher.status === 'ACTIVE' }]" :disabled="rowBusyId !== null" @click="ask(teacher.status === 'ACTIVE' ? 'disable' : 'enable', teacher)">{{ teacher.status === 'ACTIVE' ? '停用' : '启用' }}</button><button class="ghost" :disabled="rowBusyId !== null" @click="ask('reset', teacher)">重置密码</button></div></td></tr>
      </tbody></table></div>
      <nav class="admin-pager" aria-label="教师列表分页"><span>每页 {{ size }} 条</span><div class="actions"><button class="secondary" :disabled="loading || page <= 1" @click="page--">上一页</button><span>第 {{ page }} / {{ pageCount }} 页</span><button class="secondary" :disabled="loading || page >= pageCount" @click="page++">下一页</button></div></nav>
    </section>
  </div>

  <TeacherModal v-if="createOpen" title="创建教师账号" :busy="creating" @close="closeCreate">
    <p class="admin-dialog-intro">创建后将生成一次性临时密码，教师下次登录需要修改密码。</p>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <form id="admin-create-teacher" class="editor-form" @submit.prevent="create"><label>教师账号<input v-model.trim="username" required maxlength="64" pattern="[A-Za-z0-9_.\-]+" :disabled="creating" autocomplete="off" aria-describedby="admin-username-hint"></label><p id="admin-username-hint" class="admin-field-hint">可使用字母、数字、下划线、点或短横线。</p><label>教师姓名<input v-model.trim="name" required maxlength="64" :disabled="creating"></label></form>
    <template #footer><button class="secondary" :disabled="creating" @click="closeCreate">取消</button><button type="submit" form="admin-create-teacher" class="primary" :disabled="creating">{{ creating ? '创建中…' : '创建并生成临时密码' }}</button></template>
  </TeacherModal>

  <TeacherModal v-if="confirmation" :title="confirmationTitle" size="confirm" :variant="confirmation.type === 'enable' ? 'default' : 'danger'" :busy="rowBusyId !== null" @close="closeConfirmation">
    <div class="confirmation-copy"><p class="admin-confirm-object"><strong>{{ confirmation.teacher.name }}</strong><span>教师账号：{{ confirmation.teacher.username }}</span></p><p v-if="confirmation.type === 'disable'">停用后，该教师将无法登录，已有登录凭据也会失效。历史课程、考试与成绩数据保留。</p><p v-else-if="confirmation.type === 'enable'">启用后，该教师可以重新登录平台。当前密码与历史数据保持不变。</p><p v-else>旧密码将立即失效，并生成一次性临时密码。教师下次登录需要修改密码；临时密码关闭后无法再次查看。</p><p v-if="error" class="error-message" role="alert">{{ error }}</p></div>
    <template #footer><button class="secondary" :disabled="rowBusyId !== null" @click="closeConfirmation">取消</button><button :class="confirmation.type === 'enable' ? 'primary' : 'danger'" :disabled="rowBusyId !== null" @click="confirmAction">{{ rowBusyId !== null ? '处理中…' : confirmation.type === 'disable' ? '确认停用' : confirmation.type === 'enable' ? '确认启用' : '重置并生成临时密码' }}</button></template>
  </TeacherModal>
  <TeacherModal v-if="temporary" title="临时密码（仅显示一次）" size="confirm" @close="temporary = null">
    <div class="confirmation-copy"><p class="admin-confirm-object"><strong>{{ temporary.name }}</strong><span>教师账号：{{ temporary.username }}</span></p><div class="temporary-password">{{ temporary.temporaryPassword }}</div><p>请立即安全地交给教师。下次登录需要修改密码，关闭后无法再次查看。</p></div>
    <template #footer><button class="primary" @click="temporary = null">我已保存并关闭</button></template>
  </TeacherModal>
</template>
