<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { teacherApi, formatTime } from '../../api/teacher'
import { activeChapterOptions, bankFilterParams, chapterPath, compactNames, difficultyNames } from '../../utils/questionBankMetadata'
import TeacherModal from '../../components/TeacherModal.vue'
import QuestionBankItemForm from '../../components/QuestionBankItemForm.vue'
import QuestionBankStructureManager from '../../components/QuestionBankStructureManager.vue'
import QuestionBankBatchMetadata from '../../components/QuestionBankBatchMetadata.vue'
import QuestionBankExcelImport from '../../components/QuestionBankExcelImport.vue'

const route = useRoute()
const bank = ref(null), rows = ref([]), metadata = ref({ chapters: [], knowledgePoints: [], tags: [] }), allMetadata = ref({ chapters: [], knowledgePoints: [], tags: [] })
const loading = ref(true), error = ref(''), notice = ref(''), opened = ref(false), selected = ref(null)
const listLoading = ref(false)
const managerOpen = ref(false), batchOpen = ref(false), importOpen = ref(false), selectedIds = ref([])
const platformBank = computed(() => bank.value?.scope === 'PLATFORM')
const canEdit = computed(() => bank.value?.scope === 'TEACHER' && bank.value?.status === 'ACTIVE')
const filters = reactive({ keyword: '', questionType: '', status: '', chapterIds: [], knowledgePointIds: [], difficulty: '', tagIds: [] })
const typeNames = { SINGLE_CHOICE: '单选题', SHORT_ANSWER: '简答题' }
const statusNames = { DRAFT: '草稿', READY: '可用', ARCHIVED: '已归档' }
const chapterChoices = computed(() => activeChapterOptions(metadata.value.chapters))
const selectableRows = computed(() => rows.value.filter(row => row.status !== 'ARCHIVED'))
const allVisibleSelected = computed(() => selectableRows.value.length > 0 && selectableRows.value.every(row => selectedIds.value.includes(row.id)))
let listRequestSequence = 0

async function loadRows() {
  error.value = ''; listLoading.value = true
  const sequence = ++listRequestSequence
  try {
    const result = await teacherApi.bankItems(route.params.bankId, bankFilterParams(filters))
    if (sequence !== listRequestSequence) return
    rows.value = result.data
    selectedIds.value = []
  } catch (e) { if (sequence === listRequestSequence) error.value = e.message || '题目加载失败，请重新搜索' }
  finally { if (sequence === listRequestSequence) listLoading.value = false }
}
async function refreshMetadata() {
  const [active, all] = await Promise.all([teacherApi.bankMetadata(route.params.bankId), teacherApi.bankMetadata(route.params.bankId, true)])
  metadata.value = active.data
  allMetadata.value = all.data
}
async function load() {
  loading.value = true; error.value = ''
  try {
    bank.value = (await teacherApi.questionBank(route.params.bankId)).data
    await Promise.all([refreshMetadata(), loadRows()])
  } catch (e) { error.value = e.message || '题库加载失败' }
  finally { loading.value = false }
}
function edit(item) { selected.value = item; opened.value = true }
async function saved() { opened.value = false; notice.value = '题目保存成功'; await loadRows() }
async function ready(item) {
  try { await teacherApi.readyBankItem(item.id); notice.value = '题目已标记为可用'; await loadRows() }
  catch (e) { error.value = e.message || '操作失败' }
}
async function archive(item) {
  if (!window.confirm(`确定归档题目“${item.title}”吗？`)) return
  try { await teacherApi.archiveBankItem(item.id); notice.value = '题目已归档'; await loadRows() }
  catch (e) { error.value = e.message || '操作失败' }
}
function toggleVisible() {
  selectedIds.value = allVisibleSelected.value ? [] : selectableRows.value.map(row => row.id)
}
async function structureChanged() {
  try {
    await refreshMetadata()
    filters.chapterIds = filters.chapterIds.filter(id => chapterChoices.value.some(row => row.id === id))
    filters.knowledgePointIds = filters.knowledgePointIds.filter(id => metadata.value.knowledgePoints.some(row => row.id === id))
    filters.tagIds = filters.tagIds.filter(id => metadata.value.tags.some(row => row.id === id))
    await loadRows()
  }
  catch (e) { error.value = e.message || '刷新分类信息失败' }
}
async function batchSaved(count) {
  batchOpen.value = false
  selectedIds.value = []
  notice.value = `已更新 ${count} 道题的分类信息`
  await loadRows()
}
async function imported(result) {
  notice.value = `导入完成：成功 ${result.importedCount} 道，跳过 ${result.skippedCount} 道`
  await loadRows()
}
onMounted(load)
</script>

<template>
  <div class="bank-detail-page">
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <p v-if="notice" class="success-message" role="status">{{ notice }}</p>
    <p v-if="loading" class="loading-state" role="status">正在加载题库…</p>
    <template v-if="bank">
      <div class="bank-breadcrumb"><RouterLink to="/teacher/question-banks">题库管理</RouterLink><span>/</span><span>题库详情</span></div>
      <header class="page-heading"><div><h1>{{ bank.name }}</h1><div class="bank-context"><span>{{ platformBank ? '平台公共题库' : `所属课程：${bank.courseName || '未绑定课程'}` }}</span><span :class="['badge', bank.status]">{{ bank.status === 'ACTIVE' ? '可用' : '已归档' }}</span></div><p class="muted preserve">{{ bank.description || '暂无说明' }}</p></div>
        <div v-if="canEdit" class="actions"><button class="secondary" @click="managerOpen = true">分类管理</button><button class="secondary" @click="importOpen = true">Excel 批量导入</button><button class="primary" @click="edit(null)">新增题目</button></div>
      </header>
      <p v-if="platformBank" class="notice">平台公共题库，仅可查看和用于组卷。</p>
      <p v-if="canEdit && !bank.courseId"><RouterLink to="/teacher/question-banks">前往题库管理绑定课程</RouterLink></p>
      <section class="bank-filter-bar" aria-label="题目筛选">
        <div class="bank-filter-primary">
          <label>关键词<input v-model="filters.keyword" placeholder="搜索题目名称或内容" @keyup.enter="loadRows" /></label>
          <label>题型<select v-model="filters.questionType" @change="loadRows"><option value="">全部</option><option value="SINGLE_CHOICE">单选</option><option value="SHORT_ANSWER">简答</option></select></label>
          <label>状态<select v-model="filters.status" @change="loadRows"><option value="">全部</option><option value="DRAFT">草稿</option><option value="READY">可用</option><option value="ARCHIVED">已归档</option></select></label>
          <label>难度<select v-model="filters.difficulty" @change="loadRows"><option value="">全部</option><option value="EASY">简单</option><option value="MEDIUM">中等</option><option value="HARD">困难</option></select></label>
          <button class="secondary" @click="loadRows">搜索</button>
        </div>
        <div class="bank-filter-extra"><span>分类筛选</span>
          <details class="bank-filter-multi"><summary>章节{{ filters.chapterIds.length ? ` (${filters.chapterIds.length})` : '' }}</summary><div class="bank-filter-options" @change="loadRows"><label v-for="chapter in chapterChoices" :key="chapter.id"><input v-model="filters.chapterIds" type="checkbox" :value="chapter.id" />{{ chapter.label }}</label><span v-if="!chapterChoices.length" class="muted">暂无章节</span></div></details>
          <details class="bank-filter-multi"><summary>知识点{{ filters.knowledgePointIds.length ? ` (${filters.knowledgePointIds.length})` : '' }}</summary><div class="bank-filter-options" @change="loadRows"><label v-for="point in metadata.knowledgePoints" :key="point.id"><input v-model="filters.knowledgePointIds" type="checkbox" :value="point.id" />{{ point.name }}</label><span v-if="!metadata.knowledgePoints.length" class="muted">暂无知识点</span></div></details>
          <details class="bank-filter-multi"><summary>标签{{ filters.tagIds.length ? ` (${filters.tagIds.length})` : '' }}</summary><div class="bank-filter-options" @change="loadRows"><label v-for="tag in metadata.tags" :key="tag.id"><input v-model="filters.tagIds" type="checkbox" :value="tag.id" />{{ tag.name }}</label><span v-if="!metadata.tags.length" class="muted">暂无标签</span></div></details>
        </div>
      </section>
      <div v-if="canEdit && selectableRows.length" class="bank-selection-bar"><label><input type="checkbox" :checked="allVisibleSelected" @change="toggleVisible" />选择当前列表题目（{{ selectableRows.length }}）</label><span>已选择 {{ selectedIds.length }} 道题</span><button v-if="selectedIds.length" class="ghost" @click="selectedIds = []">清空选择</button><button v-if="selectedIds.length" class="primary" @click="batchOpen = true">批量设置</button></div>
      <p v-if="listLoading" class="loading-state" role="status">正在加载题目…</p>
      <div v-else-if="!error && !rows.length" class="empty-state">{{ Object.values(filters).some(value => Array.isArray(value) ? value.length : String(value).trim()) ? '没有符合条件的题目，请调整筛选条件。' : '题库暂无题目。' }}</div>
      <div v-else class="bank-question-list"><article v-for="item in rows" :key="item.id" class="bank-question">
        <header class="section-head"><div class="actions"><label v-if="canEdit && item.status !== 'ARCHIVED'" class="bank-item-select"><input v-model="selectedIds" type="checkbox" :value="item.id" :aria-label="`选择题目 ${item.title}`" /></label><span class="badge" :class="item.questionType">{{ typeNames[item.questionType] }}</span><span class="badge" :class="item.status">{{ statusNames[item.status] }}</span><strong>{{ item.defaultScore }} 分</strong></div>
          <div v-if="canEdit" class="actions"><button v-if="item.status !== 'ARCHIVED'" class="ghost" @click="edit(item)">编辑</button><button v-if="item.status === 'DRAFT'" class="ghost" @click="ready(item)">标记为可用</button><button v-if="item.status !== 'ARCHIVED'" class="danger" @click="archive(item)">归档</button></div></header>
        <h3>{{ item.title }}</h3>
        <div class="bank-item-meta"><span>难度：{{ difficultyNames[item.difficulty] || '未设置难度' }}</span><span>章节：{{ chapterPath(item.chapterId, allMetadata.chapters) }}</span><span>知识点：{{ compactNames(item.knowledgePointIds, allMetadata.knowledgePoints) }}</span><span>标签：{{ compactNames(item.tagIds, metadata.tags) }}</span></div>
        <p class="preserve">{{ item.content }}</p><div v-for="o in item.options" :key="o.key" class="option-row"><strong>{{ o.key }}.</strong> {{ o.text }}</div>
        <div class="bank-reference preserve"><strong>{{ item.questionType === 'SINGLE_CHOICE' ? '正确答案' : '参考答案' }}：</strong>{{ item.questionType === 'SINGLE_CHOICE' ? item.correctAnswer : item.referenceAnswer }}</div>
        <table v-if="item.scoringPoints.length" class="bank-scoring-table" aria-label="评分点"><thead><tr><th>评分点</th><th>分值</th></tr></thead><tbody><tr v-for="point in item.scoringPoints" :key="point.id"><td>{{ point.content }}</td><td>{{ point.score }} 分</td></tr></tbody></table><small class="bank-updated muted">更新于 {{ formatTime(item.updatedAt) }}</small>
      </article></div>
      <TeacherModal v-if="opened" :title="selected ? '编辑题目' : '新增题目'" @close="opened = false"><QuestionBankItemForm :bank-id="bank.id" :item="selected" :metadata="metadata" :all-metadata="allMetadata" @saved="saved" /></TeacherModal>
      <TeacherModal v-if="managerOpen" title="分类管理" @close="managerOpen = false"><QuestionBankStructureManager :bank-id="bank.id" @changed="structureChanged" /></TeacherModal>
      <TeacherModal v-if="batchOpen" title="批量设置" @close="batchOpen = false"><QuestionBankBatchMetadata :bank-id="bank.id" :item-ids="selectedIds" :metadata="metadata" @saved="batchSaved" /></TeacherModal>
      <TeacherModal v-if="importOpen" title="Excel 批量导入题目" @close="importOpen = false"><QuestionBankExcelImport :bank-id="bank.id" @imported="imported" /></TeacherModal>
    </template>
  </div>
</template>
