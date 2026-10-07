<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { adminApi } from '../../api/admin'
import TeacherModal from '../../components/TeacherModal.vue'
import { formatTime } from '../../api/teacher'
import { accountStatus, statusLabel, statusTone } from '../../utils/statusDisplay'

const filters = reactive({ keyword: '', status: '' })
const rows = ref([]), page = ref(1), total = ref(0), loading = ref(false)
const error = ref(''), notice = ref(''), busyId = ref(null), confirmation = ref(null), temporary = ref(null)
const size = 20
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / size)))
const confirmationTitle = computed(() => ({ enable: '确认启用学生账号', disable: '确认停用学生账号', reset: '确认重置学生密码' })[confirmation.value?.action])

async function load() {
  loading.value = true; error.value = ''
  try {
    const data = (await adminApi.students({ keyword: filters.keyword || undefined, status: filters.status || undefined, page: page.value, size })).data
    rows.value = data.items; total.value = data.total
  } catch (e) { error.value = e.message || '加载学生账号失败，请重试' }
  finally { loading.value = false }
}
function search() { page.value = 1; notice.value = ''; load() }
function clearFilters() { filters.keyword = ''; filters.status = ''; search() }
function ask(action, row) { error.value = ''; confirmation.value = { row, action } }
function closeConfirmation() { confirmation.value = null; error.value = '' }

async function confirmAction() {
  const action = confirmation.value
  if (!action) return
  busyId.value = action.row.id; error.value = ''; notice.value = ''
  try {
    if (action.action === 'reset') {
      temporary.value = (await adminApi.resetStudentPassword(action.row.id)).data
      notice.value = '学生密码已重置，临时密码仅显示一次'
    } else {
      await adminApi.setStudentStatus(action.row.id, action.action === 'enable' ? 'ACTIVE' : 'DISABLED')
      notice.value = action.action === 'enable' ? '学生账号已启用' : '学生账号已停用'
    }
    confirmation.value = null
    await load()
  } catch (e) { error.value = e.message || '操作失败，请重试' }
  finally { busyId.value = null }
}
onMounted(load)
</script>

<template>
  <div class="admin-workspace">
    <header class="page-heading"><div><h1>学生管理</h1><p class="muted">管理平台学生账号与登录状态，停用不影响历史答卷与成绩</p></div></header>
    <p v-if="error && !confirmation" class="error-message" role="alert">{{ error }}</p><p v-if="notice" class="success-message" role="status">{{ notice }}</p>
    <section class="surface admin-list-section" aria-labelledby="admin-students-title">
      <form class="admin-filter-row" @submit.prevent="search">
        <label class="admin-filter-main">学号 / 姓名<input v-model.trim="filters.keyword" placeholder="按学号或姓名搜索"></label>
        <label>账号状态<select v-model="filters.status" aria-label="账号状态"><option value="">全部状态</option><option v-for="(item, key) in accountStatus" :key="key" :value="key">{{ item.label }}</option></select></label>
        <div class="actions admin-filter-actions"><button class="secondary" :disabled="loading">{{ loading ? '查询中…' : '查询' }}</button><button type="button" class="ghost" :disabled="loading" @click="clearFilters">重置筛选</button></div>
      </form>
      <header class="section-head admin-list-head"><h2 id="admin-students-title">学生列表</h2><span class="muted">共 {{ total }} 个账号</span></header>
      <p v-if="loading" class="loading-state" role="status">正在加载学生账号…</p><p v-else-if="error" class="admin-table-message">学生列表加载失败，请重新查询。</p><p v-else-if="!rows.length" class="admin-table-message">{{ filters.keyword || filters.status ? '没有符合筛选条件的学生，请调整筛选条件。' : '暂无学生账号。' }}</p>
      <div v-else class="table-scroll"><table class="admin-student-table"><thead><tr><th scope="col">学号 / 账号</th><th scope="col">姓名</th><th scope="col">账号状态</th><th scope="col">密码状态</th><th scope="col">注册时间</th><th scope="col" class="table-action">操作</th></tr></thead><tbody>
        <tr v-for="row in rows" :key="row.id"><td>{{ row.username }}</td><td>{{ row.name }}</td><td><span class="badge" :data-tone="statusTone(accountStatus, row.status)">{{ statusLabel(accountStatus, row.status) }}</span></td><td><span class="admin-secondary-state">{{ row.mustChangePassword ? '需修改密码' : '无需改密' }}</span></td><td class="admin-time">{{ formatTime(row.createdAt) }}</td><td class="table-action"><div class="actions admin-table-actions"><button :class="['ghost', { 'admin-danger-link': row.status === 'ACTIVE' }]" :disabled="busyId !== null" @click="ask(row.status === 'ACTIVE' ? 'disable' : 'enable', row)">{{ row.status === 'ACTIVE' ? '停用' : '启用' }}</button><button class="ghost" :disabled="busyId !== null" @click="ask('reset', row)">重置密码</button></div></td></tr>
      </tbody></table></div>
      <nav class="admin-pager" aria-label="学生列表分页"><span>每页 {{ size }} 条</span><div class="actions"><button class="secondary" :disabled="loading || page <= 1" @click="page--; load()">上一页</button><span>第 {{ page }} / {{ pageCount }} 页</span><button class="secondary" :disabled="loading || page >= pageCount" @click="page++; load()">下一页</button></div></nav>
    </section>
    <TeacherModal v-if="confirmation" :title="confirmationTitle" size="confirm" :variant="confirmation.action === 'enable' ? 'default' : 'danger'" :busy="busyId !== null" @close="closeConfirmation">
      <div class="confirmation-copy"><p class="admin-confirm-object"><strong>{{ confirmation.row.name }}</strong><span>学生账号：{{ confirmation.row.username }}</span></p><p v-if="confirmation.action === 'disable'">停用后，该学生将无法登录，已有登录凭据也会失效。历史答卷与成绩数据保留。</p><p v-else-if="confirmation.action === 'enable'">启用后，该学生可以重新登录平台。当前密码与历史数据保持不变。</p><p v-else>旧密码将立即失效，并生成一次性临时密码。学生下次登录需要修改密码；临时密码关闭后无法再次查看。</p><p v-if="error" class="error-message" role="alert">{{ error }}</p></div>
      <template #footer><button class="secondary" :disabled="busyId !== null" @click="closeConfirmation">取消</button><button :class="confirmation.action === 'enable' ? 'primary' : 'danger'" :disabled="busyId !== null" @click="confirmAction">{{ busyId !== null ? '处理中…' : confirmation.action === 'disable' ? '确认停用' : confirmation.action === 'enable' ? '确认启用' : '重置并生成临时密码' }}</button></template>
    </TeacherModal>
    <TeacherModal v-if="temporary" title="临时密码（仅显示一次）" size="confirm" @close="temporary = null">
      <div class="confirmation-copy"><p class="admin-confirm-object"><strong>{{ temporary.name }}</strong><span>学生账号：{{ temporary.username }}</span></p><div class="temporary-password">{{ temporary.temporaryPassword }}</div><p>请立即安全地交给学生。下次登录需要修改密码，关闭后无法再次查看。</p></div>
      <template #footer><button class="primary" @click="temporary = null">我已保存并关闭</button></template>
    </TeacherModal>
  </div>
</template>
