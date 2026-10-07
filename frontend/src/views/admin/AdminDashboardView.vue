<script setup>
import { onMounted, ref } from 'vue'
import { adminApi } from '../../api/admin'
import { accountStatus, examBusinessStatus, statusLabel, statusTone } from '../../utils/statusDisplay'

const summary = ref(null), error = ref('')
const scales = [
  { label: '教师', field: 'teacherCount', to: '/admin/teachers', unit: '人' },
  { label: '学生', field: 'studentCount', to: '/admin/students', unit: '人' },
  { label: '课程', field: 'courseCount', to: '/admin/courses', unit: '门' },
  { label: '考试', field: 'examCount', to: '/admin/exams', unit: '场' },
]
onMounted(async () => {
  try { summary.value = (await adminApi.dashboard()).data }
  catch (e) { error.value = e.message || '加载系统概览失败，请刷新重试' }
})
</script>

<template>
  <div class="admin-workspace">
    <header class="page-heading"><div><h1>系统概览</h1><p class="muted">平台账号与教学资源总览</p></div></header>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <p v-else-if="!summary" class="loading-state" role="status">正在加载系统概览…</p>
    <section v-else class="surface admin-overview" aria-label="平台摘要">
      <div class="admin-platform-scale">
        <h2>平台规模</h2>
        <dl class="admin-scale-row">
          <div v-for="item in scales" :key="item.field">
            <dt><RouterLink :to="item.to">{{ item.label }}</RouterLink></dt>
            <dd><strong>{{ summary[item.field] }}</strong><span>{{ item.unit }}</span></dd>
          </div>
        </dl>
      </div>
      <div class="admin-overview-states">
        <section aria-labelledby="admin-account-title">
          <header class="admin-summary-head"><h2 id="admin-account-title">账号状态</h2><p>全平台账号，含管理员、教师与学生</p></header>
          <table class="admin-summary-table">
            <thead><tr><th scope="col">账号状态</th><th scope="col" class="admin-number">账号数</th></tr></thead>
            <tbody>
              <tr v-for="item in [['ACTIVE', 'activeUserCount'], ['DISABLED', 'disabledUserCount']]" :key="item[0]">
                <td><span class="badge" :data-tone="statusTone(accountStatus, item[0])">{{ statusLabel(accountStatus, item[0]) }}</span></td>
                <td class="admin-number">{{ summary[item[1]] }}</td>
              </tr>
            </tbody>
          </table>
          <div class="admin-summary-links"><RouterLink to="/admin/teachers">教师管理</RouterLink><RouterLink to="/admin/students">学生管理</RouterLink></div>
        </section>
        <section aria-labelledby="admin-exam-status-title">
          <header class="admin-summary-head"><h2 id="admin-exam-status-title">考试业务状态</h2><RouterLink to="/admin/exams">查看考试监管</RouterLink></header>
          <table class="admin-summary-table">
            <thead><tr><th scope="col">业务状态</th><th scope="col" class="admin-number">考试数</th></tr></thead>
            <tbody>
              <tr v-for="(item, key) in examBusinessStatus" :key="key">
                <td><span class="badge" :data-tone="item.tone">{{ item.label }}</span></td>
                <td class="admin-number">{{ summary.examStatuses[key] ?? 0 }}</td>
              </tr>
            </tbody>
          </table>
        </section>
      </div>
    </section>
  </div>
</template>
