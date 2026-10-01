<script setup>
import { onMounted, ref, watch } from 'vue'
import { loadWorkspace, teacherApi } from '../../api/teacher'

const exams = ref([]), examId = ref(''), summary = ref(null), questions = ref([])
const selected = ref(null), samples = ref([]), loading = ref(false), samplesLoading = ref(false)
const error = ref(''), samplesError = ref(''), examsError = ref('')
let loadId = 0, sampleId = 0
const score = value => value == null ? '—' : Number(value).toFixed(2)
const percent = value => value == null ? '—' : `${score(value)}%`
const list = value => {
  if (!value) return '—'
  try { const parsed = JSON.parse(value); return Array.isArray(parsed) ? parsed.join('、') || '—' : value }
  catch { return value }
}
async function load() {
  const id = ++loadId
  selected.value = null; samples.value = []; summary.value = null; questions.value = []; error.value = ''
  if (!examId.value) return
  loading.value = true
  const results = await Promise.allSettled([
    teacherApi.aiEvaluationSummary(examId.value), teacherApi.aiEvaluationQuestions(examId.value),
  ])
  if (id !== loadId) return
  if (results.every(result => result.status === 'fulfilled')) {
    summary.value = results[0].value.data
    questions.value = results[1].value.data || []
  } else error.value = '分析数据加载失败，请重试'
  loading.value = false
}
async function openSamples(question) {
  const id = ++sampleId
  selected.value = question; samples.value = []; samplesError.value = ''; samplesLoading.value = true
  try {
    const response = await teacherApi.aiEvaluationSamples(question.questionId)
    if (id === sampleId) samples.value = response.data.items || []
  } catch { if (id === sampleId) samplesError.value = '样本加载失败，请重试' }
  finally { if (id === sampleId) samplesLoading.value = false }
}
function closeSamples() { ++sampleId; selected.value = null; samples.value = [] }
watch(examId, load)
onMounted(async () => {
  try {
    exams.value = (await loadWorkspace()).exams.filter(exam => exam.id && (exam.title || exam.examName))
    if (exams.value.length) examId.value = String(exams.value[0].id)
  } catch { examsError.value = '考试列表加载失败，请刷新页面重试' }
})
</script>

<template>
  <div class="ai-evaluation-page">
    <header class="page-heading"><div><h1>AI评分分析</h1><p class="muted">比较 AI 建议分与教师最终分，仅包含已完成人工复核的简答题</p></div></header>
    <section class="surface evaluation-filter"><label>考试选择 <select v-model="examId"><option value="">请选择考试</option><option v-for="exam in exams" :key="exam.id" :value="String(exam.id)">{{ exam.title || exam.examName }}</option></select></label></section>
    <p v-if="examsError" class="error-message">{{ examsError }}</p>
    <p v-if="error" class="error-message">{{ error }} <button class="ghost" @click="load">重试</button></p>
    <p v-if="loading" class="empty-state" role="status">正在加载分析数据…</p>
    <template v-else-if="summary">
      <p v-if="!summary.sampleCount" class="empty-state">暂无可分析样本</p>
      <section class="evaluation-metrics" aria-label="总体指标">
        <div v-for="item in [['样本数', summary.sampleCount || '—'], ['AI平均分', score(summary.aiAverageScore)], ['教师平均分', score(summary.teacherAverageScore)], ['MAE', score(summary.mae)], ['Bias', score(summary.bias)]]" :key="item[0]" class="surface evaluation-metric"><span>{{ item[0] }}</span><strong>{{ item[1] }}</strong></div>
      </section>
      <template v-if="summary.sampleCount">
        <p class="muted evaluation-note">MAE 为平均绝对分差；Bias 为 AI 分减教师分的平均值，正值表示 AI 整体偏高，负值表示整体偏低。</p>
        <section class="surface evaluation-distribution"><h2>分差分布</h2><div v-for="item in [['相差 ≤ 1 分', summary.within1], ['相差 > 1 且 ≤ 2 分', summary.between1And2], ['相差 > 2 分', summary.over2]]" :key="item[0]" class="distribution-row"><span>{{ item[0] }}</span><div class="distribution-track"><div :style="{ width: `${item[1].percentage || 0}%` }"></div></div><strong>{{ item[1].count }} · {{ percent(item[1].percentage) }}</strong></div></section>
        <section class="evaluation-questions"><h2>题目分析</h2><p class="muted">按 MAE 从高到低排列</p><div class="table-scroll"><table><thead><tr><th>题目</th><th>样本数</th><th>AI均分</th><th>教师均分</th><th>MAE</th><th>Bias</th><th>&gt; 2分数量</th><th>操作</th></tr></thead><tbody><tr v-for="question in questions" :key="question.questionId"><td :title="question.questionContent">{{ question.questionContent }}</td><td>{{ question.sampleCount }}</td><td>{{ score(question.aiAverageScore) }}</td><td>{{ score(question.teacherAverageScore) }}</td><td>{{ score(question.mae) }}</td><td>{{ score(question.bias) }}</td><td>{{ question.over2Count }}</td><td><button class="ghost" @click="openSamples(question)">查看样本</button></td></tr></tbody></table></div></section>
      </template>
    </template>
    <section v-if="selected" class="surface evaluation-samples" aria-label="评分样本"><div class="evaluation-sample-header"><div><h2>评分样本</h2><p class="muted">{{ selected.questionContent }} · 满分 {{ score(selected.maxScore) }}</p></div><button class="ghost" @click="closeSamples">关闭</button></div><p v-if="samplesError" class="error-message">{{ samplesError }} <button class="ghost" @click="openSamples(selected)">重试</button></p><p v-else-if="samplesLoading" class="muted">正在加载样本…</p><p v-else-if="!samples.length" class="empty-state">暂无可分析样本</p><article v-for="sample in samples" :key="sample.submissionId" class="evaluation-sample"><h3>{{ sample.studentName }} <small>{{ sample.studentNo }}</small></h3><p><strong>学生答案：</strong>{{ sample.answerContent || '—' }}</p><p>AI建议分 {{ score(sample.aiScore) }} · 教师分 {{ score(sample.teacherScore) }} · 分差 {{ score(sample.difference) }}</p><p><strong>AI理由：</strong>{{ sample.aiReason || '—' }}</p><p><strong>命中评分点：</strong>{{ list(sample.matchedPoints) }}</p><p><strong>缺失评分点：</strong>{{ list(sample.missingPoints) }}</p><p><strong>教师评语：</strong>{{ sample.teacherComment || '—' }}</p></article></section>
  </div>
</template>

<style scoped>
.ai-evaluation-page { display: grid; gap: 20px; }
.evaluation-filter { padding: 18px; }
.evaluation-filter label { display: flex; align-items: center; gap: 12px; }
.evaluation-filter select { min-width: 260px; max-width: 100%; }
.evaluation-metrics { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 12px; }
.evaluation-metric { padding: 18px; display: grid; gap: 8px; }
.evaluation-metric strong { font-size: 24px; }
.evaluation-note { margin: -8px 0 0; }
.evaluation-distribution, .evaluation-samples { padding: 20px; }
.distribution-row { display: grid; grid-template-columns: 165px 1fr 130px; gap: 12px; align-items: center; margin: 14px 0; }
.distribution-track { height: 10px; border-radius: 10px; background: #e7edf3; overflow: hidden; }
.distribution-track div { height: 100%; background: #5682bb; }
.evaluation-questions h2, .evaluation-distribution h2 { margin: 0 0 8px; }
.evaluation-sample-header { display: flex; justify-content: space-between; gap: 16px; }
.evaluation-sample { border-top: 1px solid #e7edf3; padding: 16px 0; }
.evaluation-sample p { white-space: pre-wrap; overflow-wrap: anywhere; }
@media (max-width: 800px) { .evaluation-metrics { grid-template-columns: repeat(2, 1fr); } .distribution-row { grid-template-columns: 1fr; } }
</style>
