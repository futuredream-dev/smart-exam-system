<script setup>
import { computed, reactive, ref } from 'vue'
import { teacherApi } from '../api/teacher'
import { activeChapterOptions } from '../utils/questionBankMetadata'
const props = defineProps({ bankId: [Number, String], item: Object, metadata: { type: Object, default: () => ({ chapters: [], knowledgePoints: [], tags: [] }) }, allMetadata: { type: Object, default: () => ({ chapters: [], knowledgePoints: [], tags: [] }) } })
const emit = defineEmits(['saved'])
const initial = props.item
const form = reactive({ questionType: initial?.questionType || 'SINGLE_CHOICE', title: initial?.title || '', content: initial?.content || '', defaultScore: initial?.defaultScore || 5,
  correctAnswer: initial?.correctAnswer || 'A', referenceAnswer: initial?.referenceAnswer || '', options: (initial?.options?.length ? initial.options : ['A','B','C','D'].map(key => ({ key, text: '' }))).map(o => ({ ...o })),
  scoringPoints: initial?.scoringPoints?.map(p => ({ content: p.content, score: p.score, sortOrder: p.sortOrder })) || [],
  chapterId: initial?.chapterId ?? '', difficulty: initial?.difficulty || '',
  knowledgePointIds: [...(initial?.knowledgePointIds || [])], tagIds: [...(initial?.tagIds || [])] })
const busy = ref(false), error = ref('')
const chapterOptions = computed(() => activeChapterOptions(props.metadata.chapters || []))
const activePoints = computed(() => (props.metadata.knowledgePoints || []).filter(row => row.status === 'ACTIVE'))
const archivedPointIds = computed(() => form.knowledgePointIds.filter(id => !activePoints.value.some(row => row.id === id)))
const archivedChapter = computed(() => form.chapterId !== '' && !chapterOptions.value.some(row => row.id === Number(form.chapterId)))
const archivedChapterName = computed(() => props.allMetadata.chapters?.find(row => row.id === Number(form.chapterId))?.name || '已归档章节')
function archivedPointName(id) { return props.allMetadata.knowledgePoints?.find(row => row.id === id)?.name || `知识点 #${id}` }
const allocated = computed(() => (form.scoringPoints.reduce((sum, p) => sum + Math.round(Number(p.score || 0) * 100), 0) / 100).toFixed(2))
function changeType() { form.correctAnswer = form.questionType === 'SINGLE_CHOICE' ? 'A' : ''; form.referenceAnswer = ''; form.scoringPoints = [] }
function addOption() { const key = String.fromCharCode(65 + form.options.length); form.options.push({ key, text: '' }) }
async function save() { error.value = ''; busy.value = true; try { const payload = { ...form,
  options: form.questionType === 'SINGLE_CHOICE' ? form.options : null, correctAnswer: form.questionType === 'SINGLE_CHOICE' ? form.correctAnswer : null,
  referenceAnswer: form.questionType === 'SHORT_ANSWER' ? form.referenceAnswer : null,
  scoringPoints: form.questionType === 'SHORT_ANSWER' ? form.scoringPoints.map((p, index) => ({ content: p.content, score: p.score, sortOrder: index + 1 })) : [],
  chapterId: form.chapterId === '' ? null : Number(form.chapterId), difficulty: form.difficulty || null,
  knowledgePointIds: [...form.knowledgePointIds], tagIds: [...form.tagIds] }
  await teacherApi.saveBankItem(initial?.id, props.bankId, payload); emit('saved') } catch (e) { error.value = e.message } finally { busy.value = false } }
</script>
<template><form class="editor-form" @submit.prevent="save">
  <div class="form-grid"><label>题型<select v-model="form.questionType" :disabled="!!initial" @change="changeType"><option value="SINGLE_CHOICE">单选题</option><option value="SHORT_ANSWER">简答题</option></select></label><label>默认分值<input v-model.number="form.defaultScore" type="number" min="0.01" step="0.01" required /></label></div>
  <label>内部管理名称<input v-model="form.title" maxlength="160" required /></label><label>题干<textarea v-model="form.content" maxlength="10000" rows="4" required /></label>
  <template v-if="form.questionType === 'SINGLE_CHOICE'"><div v-for="(option,index) in form.options" :key="index" class="form-grid"><label>选项标识<input v-model="option.key" maxlength="32" required /></label><label>选项内容<input v-model="option.text" maxlength="2000" required /></label><button v-if="form.options.length > 2" type="button" class="danger" @click="form.options.splice(index,1)">删除</button></div><button type="button" class="secondary" @click="addOption">+ 添加选项</button><label>正确答案<select v-model="form.correctAnswer"><option v-for="o in form.options" :key="o.key" :value="o.key">{{ o.key }}</option></select></label></template>
  <template v-else><label>参考答案<textarea v-model="form.referenceAnswer" maxlength="10000" rows="4" required /></label><div class="section-head"><h3>评分点</h3><span>评分点合计 {{ allocated }} / 题目总分 {{ form.defaultScore }}</span></div>
    <div v-for="(point,index) in form.scoringPoints" :key="index" class="point-editor"><label>评分点 {{ index + 1 }}<input v-model="point.content" maxlength="10000" required /></label><label>分值<input v-model.number="point.score" type="number" min="0.01" step="0.01" required /></label><button type="button" class="danger" @click="form.scoringPoints.splice(index,1)">删除评分点</button></div>
    <button type="button" class="secondary" @click="form.scoringPoints.push({ content: '', score: 1 })">+ 添加评分点</button></template>
  <fieldset class="bank-metadata-fieldset"><legend>题目分类信息</legend>
    <div class="form-grid"><label>所属章节<select v-model="form.chapterId"><option value="">未设置</option><option v-if="archivedChapter && initial" :value="form.chapterId">{{ archivedChapterName }}（已归档）</option><option v-for="chapter in chapterOptions" :key="chapter.id" :value="chapter.id">{{ chapter.label }}</option></select></label>
      <label>难度<select v-model="form.difficulty"><option value="">未设置难度</option><option value="EASY">简单</option><option value="MEDIUM">中等</option><option value="HARD">困难</option></select></label></div>
    <div><strong>知识点</strong><p class="muted">可选多个；通过分类管理新增知识点。</p><div class="bank-choice-list"><label v-for="point in activePoints" :key="point.id"><input v-model="form.knowledgePointIds" type="checkbox" :value="point.id" />{{ point.name }}</label><span v-if="!activePoints.length" class="muted">暂无可选知识点</span></div>
      <div v-for="id in archivedPointIds" :key="id" class="bank-retained-link">{{ archivedPointName(id) }}（已归档）<button type="button" class="ghost" @click="form.knowledgePointIds = form.knowledgePointIds.filter(value => value !== id)">移除</button></div></div>
    <div><strong>标签</strong><div class="bank-choice-list"><label v-for="tag in props.metadata.tags || []" :key="tag.id"><input v-model="form.tagIds" type="checkbox" :value="tag.id" />{{ tag.name }}</label><span v-if="!props.metadata.tags?.length" class="muted">暂无标签</span></div></div>
  </fieldset>
  <p v-if="error" class="error-message" role="alert">{{ error }}</p><button class="primary" :disabled="busy">{{ busy ? '保存中…' : '保存题目' }}</button>
</form></template>
