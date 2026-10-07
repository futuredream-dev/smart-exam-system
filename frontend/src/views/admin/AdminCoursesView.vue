<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { adminApi } from '../../api/admin'
import { formatTime } from '../../api/teacher'

const filters = reactive({ keyword: '', teacherId: '' })
const rows = ref([]), page = ref(1), total = ref(0), error = ref(''), loading = ref(false)
const size = 20
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / size)))
async function load() {
  loading.value = true; error.value = ''
  try {
    const result = (await adminApi.courses({ keyword: filters.keyword || undefined, teacherId: filters.teacherId || undefined, page: page.value, size })).data
    rows.value = result.items; total.value = result.total
  } catch (e) { error.value = e.message || '加载课程列表失败，请重试' }
  finally { loading.value = false }
}
function search() { page.value = 1; load() }
function clearFilters() { filters.keyword = ''; filters.teacherId = ''; search() }
onMounted(load)
</script>

<template>
  <div class="admin-workspace">
    <header class="page-heading"><div><h1>课程监管</h1><p class="muted">查看课程归属、学生人数与考试数量</p></div><span class="admin-readonly-label">只读监管</span></header>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <section class="surface admin-list-section" aria-labelledby="admin-courses-title">
      <form class="admin-filter-row" @submit.prevent="search">
        <label class="admin-filter-main">课程 / 所属教师<input v-model.trim="filters.keyword" placeholder="按课程名称或教师姓名搜索"></label>
        <label>教师编号<input v-model="filters.teacherId" type="number" min="1" step="1" placeholder="全部教师"></label>
        <div class="actions admin-filter-actions"><button class="secondary" :disabled="loading">{{ loading ? '查询中…' : '查询' }}</button><button type="button" class="ghost" :disabled="loading" @click="clearFilters">重置筛选</button></div>
      </form>
      <header class="section-head admin-list-head"><h2 id="admin-courses-title">课程列表</h2><span class="muted">共 {{ total }} 门课程</span></header>
      <p v-if="loading" class="loading-state" role="status">正在加载课程列表…</p><p v-else-if="error" class="admin-table-message">课程列表加载失败，请重新查询。</p><p v-else-if="!rows.length" class="admin-table-message">{{ filters.keyword || filters.teacherId ? '没有符合筛选条件的课程，请调整筛选条件。' : '暂无课程。' }}</p>
      <div v-else class="table-scroll"><table class="admin-course-table"><thead><tr><th scope="col">课程</th><th scope="col">所属教师</th><th scope="col" class="admin-number">学生人数</th><th scope="col" class="admin-number">考试数量</th><th scope="col">创建时间</th></tr></thead><tbody>
        <tr v-for="row in rows" :key="row.id"><td class="admin-title-cell"><strong :title="row.courseName">{{ row.courseName }}</strong><small v-if="row.description" :title="row.description">{{ row.description }}</small></td><td>{{ row.teacherName }}<small>教师编号 {{ row.teacherId }}</small></td><td class="admin-number">{{ row.studentCount }}</td><td class="admin-number">{{ row.examCount }}</td><td class="admin-time">{{ formatTime(row.createdAt) }}</td></tr>
      </tbody></table></div>
      <nav class="admin-pager" aria-label="课程列表分页"><span>每页 {{ size }} 条</span><div class="actions"><button class="secondary" :disabled="loading || page <= 1" @click="page--; load()">上一页</button><span>第 {{ page }} / {{ pageCount }} 页</span><button class="secondary" :disabled="loading || page >= pageCount" @click="page++; load()">下一页</button></div></nav>
    </section>
  </div>
</template>
