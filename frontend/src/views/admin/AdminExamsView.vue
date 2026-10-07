<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { adminApi } from '../../api/admin'
import { formatTime } from '../../api/teacher'
import { archiveStatus, examBusinessStatus, examKind, releaseStatus, statusLabel, statusTone } from '../../utils/statusDisplay'

const filters = reactive({ keyword: '', status: '', examKind: '', archived: '' })
const rows = ref([]), page = ref(1), total = ref(0), error = ref(''), loading = ref(false)
const size = 20
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / size)))
async function load() {
  loading.value = true; error.value = ''
  try {
    const result = (await adminApi.exams({
      keyword: filters.keyword || undefined, status: filters.status || undefined,
      examKind: filters.examKind || undefined,
      archived: filters.archived === '' ? undefined : filters.archived === 'true',
      page: page.value, size,
    })).data
    rows.value = result.items; total.value = result.total
  } catch (e) { error.value = e.message || '加载考试列表失败，请重试' }
  finally { loading.value = false }
}
function search() { page.value = 1; load() }
function clearFilters() { filters.keyword = ''; filters.status = ''; filters.examKind = ''; filters.archived = ''; search() }
onMounted(load)
</script>

<template>
  <div class="admin-workspace">
    <header class="page-heading"><div><h1>考试监管</h1><p class="muted">查看考试业务状态、成绩发布与归档情况</p></div><span class="admin-readonly-label">只读监管</span></header>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <section class="surface admin-list-section" aria-labelledby="admin-exams-title">
      <form class="admin-filter-row admin-exam-filters" @submit.prevent="search">
        <label class="admin-filter-main">考试 / 课程 / 教师<input v-model.trim="filters.keyword" placeholder="按考试、课程或教师搜索"></label>
        <label>业务状态<select v-model="filters.status" aria-label="业务状态"><option value="">全部业务状态</option><option v-for="(item, key) in examBusinessStatus" :key="key" :value="key">{{ item.label }}</option></select></label>
        <label>考试类型<select v-model="filters.examKind" aria-label="考试类型"><option value="">全部类型</option><option v-for="(item, key) in examKind" :key="key" :value="key">{{ item.label }}</option></select></label>
        <label>归档状态<select v-model="filters.archived" aria-label="归档状态"><option value="">全部归档状态</option><option value="false">{{ archiveStatus.UNARCHIVED.label }}</option><option value="true">{{ archiveStatus.ARCHIVED.label }}</option></select></label>
        <div class="actions admin-filter-actions"><button class="secondary" :disabled="loading">{{ loading ? '查询中…' : '查询' }}</button><button type="button" class="ghost" :disabled="loading" @click="clearFilters">重置筛选</button></div>
      </form>
      <header class="section-head admin-list-head"><h2 id="admin-exams-title">考试列表</h2><span class="muted">共 {{ total }} 场考试</span></header>
      <p v-if="loading" class="loading-state" role="status">正在加载考试列表…</p><p v-else-if="error" class="admin-table-message">考试列表加载失败，请重新查询。</p><p v-else-if="!rows.length" class="admin-table-message">{{ filters.keyword || filters.status || filters.examKind || filters.archived ? '没有符合筛选条件的考试，请调整筛选条件。' : '暂无考试。' }}</p>
      <div v-else class="table-scroll"><table class="admin-exam-table"><thead><tr><th scope="col">考试名称</th><th scope="col">课程 / 所属教师</th><th scope="col">考试类型</th><th scope="col">当前阶段</th><th scope="col">考试时间</th><th scope="col" class="admin-number">已交 / 应考</th><th scope="col">成绩发布状态</th><th scope="col">归档状态</th></tr></thead><tbody>
        <tr v-for="row in rows" :key="row.id">
          <td class="admin-title-cell"><strong :title="row.title">{{ row.title }}</strong></td>
          <td class="admin-title-cell"><span :title="row.courseName">{{ row.courseName }}</span><small>{{ row.teacherName }}</small></td>
          <td>{{ statusLabel(examKind, row.examKind) }}</td>
          <td><span class="badge" :data-tone="statusTone(examBusinessStatus, row.status)">{{ statusLabel(examBusinessStatus, row.status) }}</span></td>
          <td class="admin-time">{{ formatTime(row.startTime) }}<small>至 {{ formatTime(row.endTime) }}</small></td>
          <td class="admin-number">{{ row.submittedCount }} / {{ row.candidateCount }}</td>
          <td><span class="admin-release-state" :data-tone="statusTone(releaseStatus, row.resultsReleasedAt ? 'RELEASED' : 'UNRELEASED')">{{ statusLabel(releaseStatus, row.resultsReleasedAt ? 'RELEASED' : 'UNRELEASED') }}</span></td>
          <td><span class="badge admin-archive-state" :data-tone="statusTone(archiveStatus, row.archivedAt ? 'ARCHIVED' : 'UNARCHIVED')">{{ statusLabel(archiveStatus, row.archivedAt ? 'ARCHIVED' : 'UNARCHIVED') }}</span></td>
        </tr>
      </tbody></table></div>
      <nav class="admin-pager" aria-label="考试列表分页"><span>每页 {{ size }} 条</span><div class="actions"><button class="secondary" :disabled="loading || page <= 1" @click="page--; load()">上一页</button><span>第 {{ page }} / {{ pageCount }} 页</span><button class="secondary" :disabled="loading || page >= pageCount" @click="page++; load()">下一页</button></div></nav>
    </section>
  </div>
</template>
