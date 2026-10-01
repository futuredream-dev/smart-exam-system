<script setup>
import { onMounted, reactive, ref } from 'vue'
import { adminApi } from '../../api/admin'
import { formatTime } from '../../api/teacher'
const filters = reactive({ keyword: '', teacherId: '' }), rows = ref([]), page = ref(1), total = ref(0), error = ref(''), loading = ref(false)
const size = 20
async function load() { loading.value = true; error.value = ''; try { const result = (await adminApi.courses({ keyword: filters.keyword || undefined, teacherId: filters.teacherId || undefined, page: page.value, size })).data; rows.value = result.items; total.value = result.total } catch (e) { error.value = e.message } finally { loading.value = false } }
function search() { page.value = 1; load() }
onMounted(load)
</script>

<template><div class="admin-workspace"><header class="page-heading"><div><h1>课程监管</h1><p class="muted">跨教师查看课程与人数，仅供监管。</p></div></header><p v-if="error" class="error-message" role="alert">{{ error }}</p>
  <form class="surface admin-filter-row" @submit.prevent="search"><input v-model.trim="filters.keyword" aria-label="搜索课程" placeholder="课程名或教师姓名"><input v-model="filters.teacherId" type="number" min="1" aria-label="教师 ID" placeholder="教师 ID（可选）"><button class="primary">查询</button></form>
  <section class="surface"><div class="section-head"><h2>课程列表</h2><span class="muted">共 {{ total }} 门</span></div><p v-if="loading" class="empty-state">正在加载…</p><p v-else-if="!rows.length" class="empty-state">没有符合条件的课程。</p><div v-else class="table-scroll"><table><thead><tr><th>课程</th><th>教师</th><th>学生人数</th><th>考试数量</th><th>创建时间</th></tr></thead><tbody><tr v-for="row in rows" :key="row.id"><td><strong>{{ row.courseName }}</strong><small>{{ row.description || '—' }}</small></td><td>{{ row.teacherName }}（#{{ row.teacherId }}）</td><td>{{ row.studentCount }}</td><td>{{ row.examCount }}</td><td>{{ formatTime(row.createdAt) }}</td></tr></tbody></table></div><div class="actions admin-pager"><button class="secondary" :disabled="page <= 1" @click="page--; load()">上一页</button><span>第 {{ page }} / {{ Math.max(1, Math.ceil(total / size)) }} 页</span><button class="secondary" :disabled="page * size >= total" @click="page++; load()">下一页</button></div></section>
</div></template>
