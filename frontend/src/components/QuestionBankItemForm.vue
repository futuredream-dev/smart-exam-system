<script setup>
import { computed, reactive, ref } from 'vue'
import { teacherApi } from '../api/teacher'
const props = defineProps({ bankId: [Number, String], item: Object })
const emit = defineEmits(['saved'])
const initial = props.item
const form = reactive({ questionType: initial?.questionType || 'SINGLE_CHOICE', title: initial?.title || '', content: initial?.content || '', defaultScore: initial?.defaultScore || 5,
  correctAnswer: initial?.correctAnswer || 'A', referenceAnswer: initial?.referenceAnswer || '', options: (initial?.options?.length ? initial.options : ['A','B','C','D'].map(key => ({ key, text: '' }))).map(o => ({ ...o })),
  scoringPoints: initial?.scoringPoints?.map(p => ({ content: p.content, score: p.score, sortOrder: p.sortOrder })) || [] })
const busy = ref(false), error = ref('')
const allocated = computed(() => (form.scoringPoints.reduce((sum, p) => sum + Math.round(Number(p.score || 0) * 100), 0) / 100).toFixed(2))
function changeType() { form.correctAnswer = form.questionType === 'SINGLE_CHOICE' ? 'A' : ''; form.referenceAnswer = ''; form.scoringPoints = [] }
function addOption() { const key = String.fromCharCode(65 + form.options.length); form.options.push({ key, text: '' }) }
async function save() { error.value = ''; busy.value = true; try { const payload = { ...form,
  options: form.questionType === 'SINGLE_CHOICE' ? form.options : null, correctAnswer: form.questionType === 'SINGLE_CHOICE' ? form.correctAnswer : null,
  referenceAnswer: form.questionType === 'SHORT_ANSWER' ? form.referenceAnswer : null,
  scoringPoints: form.questionType === 'SHORT_ANSWER' ? form.scoringPoints.map((p, index) => ({ content: p.content, score: p.score, sortOrder: index + 1 })) : [] }
  await teacherApi.saveBankItem(initial?.id, props.bankId, payload); emit('saved') } catch (e) { error.value = e.message } finally { busy.value = false } }
</script>
<template><form class="editor-form" @submit.prevent="save">
  <div class="form-grid"><label>题型<select v-model="form.questionType" :disabled="!!initial" @change="changeType"><option value="SINGLE_CHOICE">单选题</option><option value="SHORT_ANSWER">简答题</option></select></label><label>默认分值<input v-model.number="form.defaultScore" type="number" min="0.01" step="0.01" required /></label></div>
  <label>内部管理名称<input v-model="form.title" maxlength="160" required /></label><label>题干<textarea v-model="form.content" maxlength="10000" rows="4" required /></label>
  <template v-if="form.questionType === 'SINGLE_CHOICE'"><div v-for="(option,index) in form.options" :key="index" class="form-grid"><label>选项标识<input v-model="option.key" maxlength="32" required /></label><label>选项内容<input v-model="option.text" maxlength="2000" required /></label><button v-if="form.options.length > 2" type="button" class="danger" @click="form.options.splice(index,1)">删除</button></div><button type="button" class="secondary" @click="addOption">+ 添加选项</button><label>正确答案<select v-model="form.correctAnswer"><option v-for="o in form.options" :key="o.key" :value="o.key">{{ o.key }}</option></select></label></template>
  <template v-else><label>参考答案<textarea v-model="form.referenceAnswer" maxlength="10000" rows="4" required /></label><div class="section-head"><h3>评分点</h3><span>评分点合计 {{ allocated }} / 题目总分 {{ form.defaultScore }}</span></div>
    <div v-for="(point,index) in form.scoringPoints" :key="index" class="point-editor"><label>评分点 {{ index + 1 }}<input v-model="point.content" maxlength="10000" required /></label><label>分值<input v-model.number="point.score" type="number" min="0.01" step="0.01" required /></label><button type="button" class="danger" @click="form.scoringPoints.splice(index,1)">删除评分点</button></div>
    <button type="button" class="secondary" @click="form.scoringPoints.push({ content: '', score: 1 })">+ 添加评分点</button></template>
  <p v-if="error" class="error-message" role="alert">{{ error }}</p><button class="primary" :disabled="busy">{{ busy ? '保存中…' : '保存题目' }}</button>
</form></template>
