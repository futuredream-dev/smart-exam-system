<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { teacherApi, formatTime } from '../../api/teacher'
import TeacherModal from '../../components/TeacherModal.vue'
import QuestionBankItemForm from '../../components/QuestionBankItemForm.vue'
const route = useRoute(), bank = ref(null), rows = ref([]), loading = ref(true), error = ref(''), notice = ref(''), opened = ref(false), selected = ref(null)
const filters = reactive({ keyword: '', questionType: '', status: '' })
const typeNames = { SINGLE_CHOICE: '单选题', SHORT_ANSWER: '简答题' }, statusNames = { DRAFT: '草稿', READY: '可用', ARCHIVED: '已归档' }
async function load() { loading.value = true; error.value = ''; try { const [b, items] = await Promise.all([teacherApi.questionBank(route.params.bankId), teacherApi.bankItems(route.params.bankId, Object.fromEntries(Object.entries(filters).filter(([,v]) => v))) ]); bank.value = b.data; rows.value = items.data } catch (e) { error.value = e.message } finally { loading.value = false } }
function edit(item) { selected.value = item; opened.value = true }
async function saved() { opened.value = false; notice.value = '题目保存成功，核心内容修改后状态为草稿'; await load() }
async function ready(item) { try { await teacherApi.readyBankItem(item.id); notice.value = '题目已标记为可用'; await load() } catch (e) { error.value = e.message } }
async function archive(item) { if (!window.confirm(`确定归档题目“${item.title}”吗？`)) return; try { await teacherApi.archiveBankItem(item.id); notice.value = '题目已归档'; await load() } catch (e) { error.value = e.message } }
onMounted(load)
</script>
<template>
  <p v-if="error" class="error-message" role="alert">{{ error }}</p><p v-if="notice" class="success-message" role="status">{{ notice }}</p><p v-if="loading" class="empty-state">正在加载题库…</p>
  <template v-if="bank"><RouterLink to="/teacher/question-banks">← 返回题库管理</RouterLink>
    <header class="page-heading"><div><p class="eyebrow">QUESTION BANK</p><h1>{{ bank.name }}</h1><p class="muted preserve">{{ bank.description || '暂无说明' }}</p></div><button v-if="bank.status === 'ACTIVE'" class="primary" @click="edit(null)">+ 新增题目</button></header>
    <section class="surface editor-form"><div class="form-grid"><label>搜索题目<input v-model="filters.keyword" placeholder="title / content" @keyup.enter="load" /></label><label>题型<select v-model="filters.questionType" @change="load"><option value="">全部</option><option value="SINGLE_CHOICE">单选</option><option value="SHORT_ANSWER">简答</option></select></label><label>状态<select v-model="filters.status" @change="load"><option value="">全部</option><option value="DRAFT">草稿</option><option value="READY">可用</option><option value="ARCHIVED">已归档</option></select></label></div><button class="secondary" @click="load">搜索</button></section>
    <div v-if="!rows.length" class="empty-state">没有符合条件的题目。</div><article v-for="item in rows" :key="item.id" class="surface question-card">
      <header class="section-head"><div class="actions"><span class="badge" :class="item.questionType">{{ typeNames[item.questionType] }}</span><span class="badge" :class="item.status">{{ statusNames[item.status] }}</span><strong>{{ item.defaultScore }} 分</strong></div><div v-if="bank.status === 'ACTIVE'" class="actions"><button v-if="item.status !== 'ARCHIVED'" class="ghost" @click="edit(item)">编辑</button><button v-if="item.status === 'DRAFT'" class="primary" @click="ready(item)">标记为可用</button><button v-if="item.status !== 'ARCHIVED'" class="danger" @click="archive(item)">归档</button></div></header>
      <h3>{{ item.title }}</h3><p class="preserve">{{ item.content }}</p><div v-for="o in item.options" :key="o.key" class="option-row"><strong>{{ o.key }}.</strong> {{ o.text }}</div>
      <div class="answer-preview preserve"><strong>{{ item.questionType === 'SINGLE_CHOICE' ? '正确答案' : '参考答案' }}：</strong>{{ item.questionType === 'SINGLE_CHOICE' ? item.correctAnswer : item.referenceAnswer }}</div>
      <ol v-if="item.scoringPoints.length"><li v-for="point in item.scoringPoints" :key="point.id">{{ point.content }} <strong>{{ point.score }} 分</strong></li></ol><small class="muted">更新于 {{ formatTime(item.updatedAt) }}</small>
    </article>
    <TeacherModal v-if="opened" :title="selected ? '编辑题目' : '新增题目'" @close="opened = false"><QuestionBankItemForm :bank-id="bank.id" :item="selected" @saved="saved" /></TeacherModal>
  </template>
</template>
