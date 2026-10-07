<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { formatTime, loadWorkspace, teacherApi } from '../../api/teacher'
import { releaseStatus, submissionStatus, statusLabel, statusTone } from '../../utils/statusDisplay'

const route = useRoute(), router = useRouter()
const validTaskStatuses = ['PENDING_REVIEW', 'REVIEWED', 'PENDING_RELEASE', 'RELEASED']
const validAiStatuses = ['SUCCESS', 'FAILED', 'NOT_CONFIGURED', 'NO_RESULT']
const parsePage = value => Math.max(1, Number.parseInt(value, 10) || 1)
const parseFilter = (value, allowed) => allowed.includes(value) ? value : ''
const applied = computed(() => ({
  examId: /^\d+$/.test(String(route.query.examId || '')) && Number(route.query.examId) > 0 ? String(route.query.examId) : '',
  taskStatus: parseFilter(route.query.taskStatus, validTaskStatuses),
  aiStatus: parseFilter(route.query.aiStatus, validAiStatuses),
  keyword: String(route.query.keyword || '').trim().slice(0, 200),
  page: parsePage(route.query.page),
}))
const draft = reactive({ examId: '', taskStatus: '', aiStatus: '', keyword: '' })
const rows = ref([]), total = ref(0), summary = ref(null), exams = ref([])
const loading = ref(false), summaryLoading = ref(false), error = ref(''), summaryError = ref(''), examsError = ref('')
let requestId = 0
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / 20)))
const summaryItems = [
  ['全部', 'total', '', ''], ['待教师复核', 'pendingReview', 'PENDING_REVIEW', ''],
  ['AI异常', 'aiFailed', '', 'FAILED'], ['待发布', 'pendingRelease', 'PENDING_RELEASE', ''],
  ['成绩已发布', 'released', 'RELEASED', ''],
]
function queryFor(filters) {
  const query = {}
  if (filters.examId) query.examId = filters.examId
  if (filters.taskStatus) query.taskStatus = filters.taskStatus
  if (filters.aiStatus) query.aiStatus = filters.aiStatus
  if (filters.keyword?.trim()) query.keyword = filters.keyword.trim().slice(0, 200)
  if (filters.page > 1) query.page = String(filters.page)
  return query
}
function apiParams() { return { ...queryFor(applied.value), page: applied.value.page, size: 20 } }
function syncDraft() { Object.assign(draft, { examId: applied.value.examId, taskStatus: applied.value.taskStatus, aiStatus: applied.value.aiStatus, keyword: applied.value.keyword }) }
function search() { router.push({ path: route.path, query: queryFor({ ...draft, page: 1 }) }) }
function reset() { Object.assign(draft, { examId: '', taskStatus: '', aiStatus: '', keyword: '' }); router.push({ path: route.path }) }
function changePage(page) { if (page >= 1 && page <= pageCount.value) router.push({ path: route.path, query: queryFor({ ...applied.value, page }) }) }
function selectSummary(taskStatus, aiStatus) { router.push({ path: route.path, query: queryFor({ ...applied.value, taskStatus, aiStatus, page: 1 }) }) }
function selectedSummary(taskStatus, aiStatus) { return applied.value.taskStatus === taskStatus && applied.value.aiStatus === aiStatus }
function paperLink(row) { return { path: `/teacher/exams/${row.examId}/grading/submissions/${row.submissionId}`, query: { from: 'grading-tasks', ...queryFor(applied.value) } } }
function aiParts(row) {
  if (!row.aiRequiredCount) return [{ label: '不适用', type: '' }]
  const parts = [
    [row.aiSuccessCount, '成功', ''], [row.aiFailedCount, 'AI失败', 'task-exception'],
    [row.aiNotConfiguredCount, '未配置', 'task-warning'], [row.aiNoResultCount, '未生成', 'task-warning'],
  ].filter(([count]) => count > 0).map(([count, label, type]) => ({ label: `${label} ${count}`, type }))
  return parts.length ? parts : [{ label: '暂无结果', type: '' }]
}
async function load() {
  const id = ++requestId, params = apiParams()
  loading.value = true; summaryLoading.value = true; error.value = ''; summaryError.value = ''
  const [listResult, summaryResult] = await Promise.allSettled([
    teacherApi.gradingTasks(params), teacherApi.gradingTaskSummary(params),
  ])
  if (id !== requestId) return
  if (listResult.status === 'fulfilled') {
    rows.value = listResult.value.data.items || []
    total.value = listResult.value.data.total || 0
    if (total.value > 0 && params.page > Math.ceil(total.value / 20)) {
      router.replace({ path: route.path, query: queryFor({ ...applied.value, page: Math.ceil(total.value / 20) }) })
      return
    }
  } else { error.value = '阅卷任务加载失败，请重试'; rows.value = []; total.value = 0 }
  if (summaryResult.status === 'fulfilled') summary.value = summaryResult.value.data
  else { summary.value = null; summaryError.value = '任务概览加载失败，请重试' }
  loading.value = false; summaryLoading.value = false
}
watch(() => route.fullPath, () => { syncDraft(); load() })
onMounted(async () => {
  syncDraft(); load()
  try { exams.value = (await loadWorkspace()).exams.filter(exam => exam.id && (exam.title || exam.examName)) }
  catch { examsError.value = '考试选项暂不可用'; }
})
</script>

<template>
  <div class="grading-task-page">
    <header class="page-heading"><div><h1>阅卷任务</h1><p class="muted">统一查看并处理当前教师名下考试的阅卷进度</p></div></header>
    <section class="task-summary" aria-label="任务概览">
      <div class="task-section-title"><h2>任务概览</h2><small>当前筛选范围，不受分页影响</small></div>
      <p v-if="summaryLoading && !summary" class="muted">正在加载任务概览…</p>
      <p v-else-if="summaryError" class="error-message">{{ summaryError }} <button class="ghost" @click="load">重试</button></p>
      <template v-else-if="summary">
        <div class="task-summary-grid"><button v-for="[label, key, taskStatus, aiStatus] in summaryItems" :key="key" type="button" :class="['task-summary-item', { selected: selectedSummary(taskStatus, aiStatus) }]" :aria-pressed="selectedSummary(taskStatus, aiStatus)" @click="selectSummary(taskStatus, aiStatus)"><span>{{ label }}</span><strong>{{ summary[key] }}</strong></button></div>
        <p class="task-summary-extra">AI未配置 {{ summary.aiNotConfigured }} · AI未生成 {{ summary.aiNoResult }}</p>
      </template>
    </section>
    <form class="task-filters surface" @submit.prevent="search">
      <label>考试<select v-model="draft.examId"><option value="">全部考试</option><option v-for="exam in exams" :key="exam.id" :value="String(exam.id)">{{ exam.title || exam.examName }}</option></select></label>
      <label>任务状态<select v-model="draft.taskStatus"><option value="">全部</option><option value="PENDING_REVIEW">待教师复核</option><option value="REVIEWED">已复核</option><option value="PENDING_RELEASE">待发布成绩</option><option value="RELEASED">成绩已发布</option></select></label>
      <label>AI状态<select v-model="draft.aiStatus"><option value="">全部</option><option value="SUCCESS">全部成功</option><option value="FAILED">AI失败</option><option value="NOT_CONFIGURED">AI未配置</option><option value="NO_RESULT">AI未生成</option></select></label>
      <label>关键字<input v-model="draft.keyword" type="search" maxlength="200" placeholder="学生姓名 / 学号"></label>
      <div class="task-filter-actions"><button class="secondary" type="submit">查询</button><button class="ghost" type="button" @click="reset">重置</button></div>
      <small v-if="examsError" class="task-filter-note">{{ examsError }}，可使用其他筛选条件</small>
    </form>
    <section class="task-results">
      <div class="task-section-title"><h2>任务列表</h2><small v-if="!loading && !error">共 {{ total }} 条</small></div>
      <p v-if="error" class="error-message">{{ error }} <button class="ghost" @click="load">重试</button></p>
      <p v-else-if="loading" class="empty-state" role="status">正在加载阅卷任务…</p>
      <p v-else-if="!total" class="empty-state">暂无符合条件的阅卷任务</p>
      <template v-else>
        <div class="table-scroll"><table><thead><tr><th>学生</th><th>考试</th><th>提交状态</th><th>阅卷状态</th><th>AI状态</th><th>得分</th><th>成绩发布</th><th>提交时间</th><th class="table-action">操作</th></tr></thead><tbody><tr v-for="row in rows" :key="row.submissionId">
          <td><strong>{{ row.studentName }}</strong><small>{{ row.studentNo }}</small></td>
          <td class="task-exam-name" :title="row.examName">{{ row.examName }}</td>
          <td class="task-plain-status">{{ row.submissionStatus ? statusLabel(submissionStatus, row.submissionStatus) : '—' }}</td>
          <td><span :class="['badge', !row.manualReviewCompleted && row.manualRequiredCount ? 'PENDING_REVIEW' : 'REVIEWED']">{{ !row.manualRequiredCount ? '无需复核' : row.manualReviewCompleted ? '已复核' : '待复核' }}</span><small v-if="row.manualRequiredCount">{{ row.manualReviewedCount }} / {{ row.manualRequiredCount }}</small></td>
          <td><span v-for="(part, index) in aiParts(row)" :key="index" :class="['task-ai-text', part.type]">{{ part.label }}</span></td>
          <td>{{ row.totalScore ?? '—' }}</td>
          <td><span class="badge" :data-tone="statusTone(releaseStatus, row.releaseStatus)">{{ row.releaseStatus ? statusLabel(releaseStatus, row.releaseStatus) : '—' }}</span></td>
          <td>{{ formatTime(row.submitTime) }}</td>
          <td class="table-action"><RouterLink class="ghost" :to="paperLink(row)">{{ !row.manualReviewCompleted && row.manualRequiredCount ? '去阅卷' : '查看答卷' }}</RouterLink></td>
        </tr></tbody></table></div>
        <div class="task-pagination"><span>第 {{ applied.page }} / {{ pageCount }} 页</span><div class="actions"><button class="secondary" :disabled="applied.page <= 1" @click="changePage(applied.page - 1)">上一页</button><button class="secondary" :disabled="applied.page >= pageCount" @click="changePage(applied.page + 1)">下一页</button></div></div>
      </template>
    </section>
  </div>
</template>
