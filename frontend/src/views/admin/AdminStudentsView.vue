<script setup>
import { onMounted, reactive, ref } from 'vue'
import { adminApi } from '../../api/admin'
import TeacherModal from '../../components/TeacherModal.vue'
import { formatTime } from '../../api/teacher'

const filters = reactive({ keyword: '', status: '' })
const rows = ref([]), page = ref(1), total = ref(0), loading = ref(false)
const error = ref(''), notice = ref(''), busyId = ref(null), confirmRow = ref(null), temporary = ref(null)
const size = 20
async function load() {
  loading.value = true; error.value = ''
  try {
    const data = (await adminApi.students({ keyword: filters.keyword || undefined, status: filters.status || undefined, page: page.value, size })).data
    rows.value = data.items; total.value = data.total
  } catch (e) { error.value = e.message } finally { loading.value = false }
}
function search() { page.value = 1; load() }
async function setStatus(row) {
  busyId.value = row.id; error.value = ''; notice.value = ''
  try { await adminApi.setStudentStatus(row.id, row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'); confirmRow.value = null; notice.value = '学生状态已更新'; await load() }
  catch (e) { error.value = e.message } finally { busyId.value = null }
}
async function reset(row) {
  busyId.value = row.id; error.value = ''; notice.value = ''
  try { temporary.value = (await adminApi.resetStudentPassword(row.id)).data; notice.value = '临时密码已生成'; confirmRow.value = null; await load() }
  catch (e) { error.value = e.message } finally { busyId.value = null }
}
onMounted(load)
</script>

<template>
  <div class="admin-workspace">
    <header class="page-heading"><div><h1>学生管理</h1><p class="muted">查看和管理平台学生账号。停用不影响历史答卷与成绩。</p></div></header>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p><p v-if="notice" class="success-message" role="status">{{ notice }}</p>
    <form class="surface admin-filter-row" @submit.prevent="search"><input v-model.trim="filters.keyword" aria-label="搜索学生" placeholder="按学号或姓名搜索"><select v-model="filters.status" aria-label="账号状态"><option value="">全部状态</option><option value="ACTIVE">启用</option><option value="DISABLED">停用</option></select><button class="primary">查询</button></form>
    <section class="surface"><div class="section-head"><h2>学生列表</h2><span class="muted">共 {{ total }} 人</span></div>
      <p v-if="loading" class="empty-state">正在加载…</p><p v-else-if="!rows.length" class="empty-state">没有符合条件的学生。</p>
      <div v-else class="table-scroll"><table><thead><tr><th>学号 / 账号</th><th>姓名</th><th>状态</th><th>改密要求</th><th>注册时间</th><th class="table-action">操作</th></tr></thead><tbody><tr v-for="row in rows" :key="row.id"><td>{{ row.username }}</td><td>{{ row.name }}</td><td><span :class="['badge', row.status]">{{ row.status === 'ACTIVE' ? '启用' : '停用' }}</span></td><td>{{ row.mustChangePassword ? '需修改' : '已完成' }}</td><td>{{ formatTime(row.createdAt) }}</td><td class="table-action"><div class="actions"><button :class="row.status === 'ACTIVE' ? 'danger' : 'secondary'" :disabled="busyId === row.id" @click="row.status === 'ACTIVE' ? (confirmRow = { row, action: 'disable' }) : setStatus(row)">{{ row.status === 'ACTIVE' ? '停用' : '启用' }}</button><button class="ghost" :disabled="busyId === row.id" @click="confirmRow = { row, action: 'reset' }">重置密码</button></div></td></tr></tbody></table></div>
      <div class="actions admin-pager"><button class="secondary" :disabled="page <= 1" @click="page--; load()">上一页</button><span>第 {{ page }} / {{ Math.max(1, Math.ceil(total / size)) }} 页</span><button class="secondary" :disabled="page * size >= total" @click="page++; load()">下一页</button></div>
    </section>
    <TeacherModal v-if="confirmRow" :title="confirmRow.action === 'disable' ? '确认停用学生' : '确认重置学生密码'" @close="confirmRow = null"><p>{{ confirmRow.row.name }}（{{ confirmRow.row.username }}）</p><p class="muted">{{ confirmRow.action === 'disable' ? '停用后当前 JWT 将失效，历史数据保留。' : '旧密码将失效，学生下次登录必须修改临时密码。' }}</p><div class="actions"><button class="secondary" @click="confirmRow = null">取消</button><button class="primary" :disabled="busyId !== null" @click="confirmRow.action === 'disable' ? setStatus(confirmRow.row) : reset(confirmRow.row)">确认</button></div></TeacherModal>
    <TeacherModal v-if="temporary" title="临时密码（仅显示一次）" @close="temporary = null"><p>{{ temporary.name }}（{{ temporary.username }}）</p><div class="temporary-password">{{ temporary.temporaryPassword }}</div><p class="muted">请安全地交给学生。关闭后无法再次查看。</p><button class="primary" @click="temporary = null">关闭</button></TeacherModal>
  </div>
</template>
