<script setup>
import { computed, reactive, ref } from 'vue'
import { teacherApi } from '../api/teacher'
const props = defineProps({ examId: [Number, String], question: Object, nextOrder: Number })
const emit = defineEmits(['saved'])
const initial = props.question
const form = reactive({
  questionType: initial?.questionType || 'SINGLE_CHOICE', content: initial?.content || '',
  score: initial?.score || 5, referenceAnswer: initial?.referenceAnswer || 'A',
  sortOrder: initial?.sortOrder ?? props.nextOrder ?? 1,
  options: ['A','B','C','D'].map(key => ({ key, text: initial?.options?.find(x => x.key === key)?.text || '' })),
  scoringPoints: initial?.scoringPoints?.map(p => ({ ...p })) || [],
})
const busy = ref(false), error = ref('')
const allocated = computed(() => (form.scoringPoints.reduce((s, p) => s + Math.round(Number(p.pointScore || 0) * 100), 0) / 100).toFixed(2))
function changeType() { form.referenceAnswer = form.questionType === 'SINGLE_CHOICE' ? 'A' : ''; form.scoringPoints = [] }
function move(index, offset) {
  const next = index + offset
  if (next < 0 || next >= form.scoringPoints.length) return
  const [point] = form.scoringPoints.splice(index, 1)
  form.scoringPoints.splice(next, 0, point)
}
async function save() {
  error.value = ''
  if (form.questionType === 'SHORT_ANSWER' && Number(allocated.value) > Number(form.score)) { error.value = '评分点总分不得超过题目分值'; return }
  busy.value = true
  try {
    const payload = { ...form, options: form.questionType === 'SINGLE_CHOICE' ? form.options : null,
      scoringPoints: form.questionType === 'SHORT_ANSWER' ? form.scoringPoints.map((p, i) => ({ pointContent: p.pointContent, pointScore: p.pointScore, sortOrder: i + 1 })) : [] }
    await teacherApi.saveQuestion(initial?.id, props.examId, payload)
    emit('saved')
  } catch (e) { error.value = e.message } finally { busy.value = false }
}
</script>
<template>
  <form class="editor-form" @submit.prevent="save">
    <div class="form-grid">
      <label>题目类型<select v-model="form.questionType" @change="changeType"><option value="SINGLE_CHOICE">单选题</option><option value="SHORT_ANSWER">简答题</option></select></label>
      <label>分值<input v-model.number="form.score" type="number" min="0.01" max="999999.99" step="0.01" required /></label>
    </div>
    <label>题目内容<textarea v-model="form.content" required rows="3" maxlength="10000" /></label>
    <template v-if="form.questionType === 'SINGLE_CHOICE'">
      <label v-for="option in form.options" :key="option.key">{{ option.key }} 选项<input v-model="option.text" required maxlength="2000" /></label>
      <label>正确答案<select v-model="form.referenceAnswer"><option v-for="key in ['A','B','C','D']" :key="key">{{ key }}</option></select></label>
    </template>
    <template v-else>
      <label>参考答案<textarea v-model="form.referenceAnswer" required rows="3" maxlength="10000" /></label>
      <div class="section-head"><h3>评分点</h3><span>当前已分配：{{ allocated }} / {{ form.score }} 分</span></div>
      <div v-for="(point, index) in form.scoringPoints" :key="index" class="point-editor">
        <label>评分点 {{ index + 1 }} 内容<input v-model="point.pointContent" required maxlength="10000" /></label>
        <label>评分点 {{ index + 1 }} 分值<input v-model.number="point.pointScore" type="number" min="0.01" step="0.01" required /></label>
        <div class="actions">
          <button type="button" class="ghost" :disabled="index === 0" @click="move(index, -1)">上移</button>
          <button type="button" class="ghost" :disabled="index === form.scoringPoints.length - 1" @click="move(index, 1)">下移</button>
          <button type="button" class="danger" @click="form.scoringPoints.splice(index, 1)">移除评分点</button>
        </div>
      </div>
      <button type="button" class="secondary" @click="form.scoringPoints.push({ pointContent: '', pointScore: 1 })">+ 新增评分点</button>
      <small class="muted">评分点可选，总分不得超过题目分值。调整顺序后点击保存题目。</small>
    </template>
    <label>排序<input v-model.number="form.sortOrder" type="number" min="0" step="1" required /></label>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <button class="primary" :disabled="busy">{{ busy ? '保存中…' : '保存题目' }}</button>
  </form>
</template>
