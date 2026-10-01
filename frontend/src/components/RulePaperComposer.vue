<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { teacherApi } from '../api/teacher'
import { activeChapterOptions, banksForExam, chapterPath, compactNames, difficultyNames } from '../utils/questionBankMetadata'
import { changeRuleBank, confirmPayload, createRule, invalidateResults, rulesPayload, summarizeRules, validateRules } from '../utils/rulePaperComposer'
import { copyRules, createAiRule, createRequestGate } from '../utils/aiPaperComposer'

const props = defineProps({ examId: [Number, String], courseId: [Number, String],
  aiMode: { type: Boolean, default: false }, requirement: { type: String, default: '' },
  intent: { type: Object, default: null },
  initialRules: { type: Array, default: null } })
const emit = defineEmits(['saved', 'close', 'draft-change', 'busy-change'])
const banks = ref([]), rules = ref(props.initialRules?.length ? copyRules(props.initialRules) : [props.aiMode ? createAiRule() : createRule()])
const metadataByBank = reactive({}), metadataLoading = reactive({}), metadataErrors = reactive({})
const state = reactive({ stage: 'CONFIG', diagnoseResult: null, previewResult: null,
  diagnosing: false, previewing: false, confirming: false })
const loadingBanks = ref(true), error = ref(''), notice = ref('')
const busy = computed(() => state.diagnosing || state.previewing || state.confirming)
const summary = computed(() => summarizeRules(rules.value))
const gate = createRequestGate()

watch(rules, () => { gate.invalidate(); invalidateResults(state); notice.value = ''; emit('draft-change', copyRules(rules.value)) }, { deep: true, flush: 'sync' })
watch(busy, value => emit('busy-change', value), { immediate: true })

async function loadBanks() {
  loadingBanks.value = true
  try { banks.value = banksForExam((await teacherApi.questionBanks()).data, props.courseId) }
  catch (e) { error.value = e.message || '题库加载失败' }
  finally { loadingBanks.value = false }
}

async function loadMetadata(bankId) {
  if (!bankId || metadataByBank[bankId] || metadataLoading[bankId]) return
  metadataLoading[bankId] = true
  metadataErrors[bankId] = ''
  try { metadataByBank[bankId] = (await teacherApi.bankMetadata(bankId)).data }
  catch (e) { metadataErrors[bankId] = e.message || '题库分类加载失败'; error.value = metadataErrors[bankId] }
  finally { metadataLoading[bankId] = false }
}

function selectBank(rule, bankId) {
  changeRuleBank(rule, bankId)
  error.value = ''
  loadMetadata(bankId)
}
function retryMetadata(bankId) { error.value = ''; loadMetadata(bankId) }

function metadata(rule) { return metadataByBank[rule.bankId] || { chapters: [], knowledgePoints: [], tags: [] } }
function chapterOptions(rule) { return activeChapterOptions(metadata(rule).chapters) }
function pointOptions(rule) { return metadata(rule).knowledgePoints.filter(item => item.status === 'ACTIVE') }

function addRule() { if (!busy.value) rules.value.push(props.aiMode ? createAiRule() : createRule()) }
function removeRule(index) { if (!busy.value) rules.value.splice(index, 1) }

function prepareRequest() {
  error.value = ''; notice.value = ''
  const invalid = validateRules(rules.value)
  if (invalid) { error.value = invalid; return null }
  if (rules.value.some(rule => !metadataByBank[rule.bankId])) {
    error.value = '请等待所选题库的章节、知识点和标签加载完成'
    return null
  }
  return rulesPayload(rules.value)
}

async function diagnose() {
  if (busy.value) return
  const request = prepareRequest()
  if (!request) return
  state.diagnosing = true
  invalidateResults(state)
  const revision = gate.capture()
  try { const result = (await (props.aiMode
      ? teacherApi.diagnoseAiAssembly(props.examId, { ...request, requirement: props.requirement, intent: props.intent })
      : teacherApi.diagnoseRuleAssembly(props.examId, request))).data
    if (gate.current(revision)) state.diagnoseResult = result }
  catch (e) { if (gate.current(revision)) error.value = e.message || '规则诊断失败' }
  finally { state.diagnosing = false }
}

async function generatePreview() {
  if (busy.value || !state.diagnoseResult?.feasible) return
  const request = prepareRequest()
  if (!request) return
  state.previewing = true
  const revision = gate.capture()
  try {
    const result = props.aiMode
      ? (await teacherApi.recommendAiAssembly(props.examId, { ...request, requirement: props.requirement, intent: props.intent })).data
      : { preview: (await teacherApi.previewRuleAssembly(props.examId, request)).data, fallback: false }
    if (!gate.current(revision)) return
    state.previewResult = result.preview
    state.stage = 'PREVIEW'
  } catch (e) {
    if (gate.current(revision)) { invalidateResults(state); error.value = e.message || '试卷预览失败' }
  } finally { state.previewing = false }
}

async function regenerate() {
  if (busy.value || !state.previewResult) return
  state.previewing = true; error.value = ''; notice.value = ''
  const revision = gate.capture()
  try {
    const request = rulesPayload(rules.value)
    const result = props.aiMode
      ? (await teacherApi.recommendAiAssembly(props.examId, { ...request, requirement: props.requirement, intent: props.intent })).data
      : { preview: (await teacherApi.previewRuleAssembly(props.examId, request)).data, fallback: false }
    if (!gate.current(revision)) return
    state.previewResult = result.preview
  }
  catch (e) {
    if (gate.current(revision)) { invalidateResults(state); error.value = `${e.message || '重新生成失败'}；请重新检查规则并生成预览。` }
  } finally { state.previewing = false }
}

function backToRules() { if (!busy.value) { invalidateResults(state); error.value = '' } }

async function confirmAdd() {
  if (busy.value || state.stage !== 'PREVIEW' || !state.previewResult?.selectedQuestions?.length) return
  state.confirming = true; error.value = ''
  const count = state.previewResult.selectedQuestions.length
  try {
    await teacherApi.confirmRuleAssembly(props.examId, confirmPayload(rules.value, state.previewResult))
    rules.value = [createRule()]
    invalidateResults(state)
    emit('saved', count)
  } catch (e) {
    invalidateResults(state)
    error.value = `${e.message || '确认加入失败'}；题库数据或考试状态可能已变化，请重新检查规则并生成预览。`
  } finally { state.confirming = false }
}

function localShortage(rule) { return ['easy', 'medium', 'hard'].some(level => rule[level]?.candidateCount < rule[level]?.requestedCount) }
const anyLocalShortage = computed(() => state.diagnoseResult?.rules?.some(localShortage) ?? false)
function selectedMetadata(item) { return metadata(rules.value[item.ruleIndex] || {}) }
onMounted(() => { loadBanks(); for (const rule of rules.value) if (rule.bankId) loadMetadata(rule.bankId)
  emit('draft-change', copyRules(rules.value)) })
</script>

<template>
  <div class="rule-paper-composer editor-form">
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <p v-if="notice" class="success-message" role="status">{{ notice }}</p>

    <template v-if="state.stage === 'CONFIG'">
      <p class="muted">按题库、分类、题型和难度设置规则。先检查候选，再生成固定试卷预览。</p>
      <p v-if="loadingBanks" class="muted">正在加载可用题库…</p>
      <p v-else-if="!banks.length" class="empty-state">暂无可用于本场考试的题库。</p>
      <fieldset :disabled="busy || loadingBanks" class="rule-edit-fields">
        <section v-for="(rule, index) in rules" :key="rule" class="rule-block">
          <div class="section-head"><h3>规则 {{ index + 1 }}</h3><button type="button" class="danger" @click="removeRule(index)">删除规则</button></div>
          <div class="form-grid">
            <label>题库
              <select :value="rule.bankId" @change="selectBank(rule, $event.target.value)">
                <option value="">请选择题库</option>
                <option v-for="bank in banks" :key="bank.id" :value="bank.id">{{ bank.name }}{{ bank.scope === 'PLATFORM' ? '（平台题库）' : '' }}</option>
              </select>
            </label>
            <label>题型<select v-model="rule.questionType"><option v-if="aiMode" value="">请选择题型</option><option value="SINGLE_CHOICE">单选题</option><option value="SHORT_ANSWER">简答题</option></select></label>
          </div>
          <p v-if="rule.bankId && metadataLoading[rule.bankId]" class="muted">正在加载分类信息…</p>
          <p v-if="rule.bankId && metadataErrors[rule.bankId]" class="error-message">分类加载失败：{{ metadataErrors[rule.bankId] }} <button type="button" class="ghost" @click="retryMetadata(rule.bankId)">重试加载</button></p>
          <fieldset v-if="rule.bankId" class="bank-metadata-fieldset" :disabled="!metadataByBank[rule.bankId]">
            <legend>分类条件（可多选）</legend>
            <div><strong>章节</strong><div class="bank-choice-list"><label v-for="chapter in chapterOptions(rule)" :key="chapter.id"><input v-model="rule.chapterIds" type="checkbox" :value="chapter.id" />{{ chapter.label }}</label><span v-if="!chapterOptions(rule).length" class="muted">暂无可选章节</span></div></div>
            <div><strong>知识点</strong><div class="bank-choice-list"><label v-for="point in pointOptions(rule)" :key="point.id"><input v-model="rule.knowledgePointIds" type="checkbox" :value="point.id" />{{ point.name }}</label><span v-if="!pointOptions(rule).length" class="muted">暂无可选知识点</span></div></div>
            <div><strong>标签</strong><div class="bank-choice-list"><label v-for="tag in metadata(rule).tags" :key="tag.id"><input v-model="rule.tagIds" type="checkbox" :value="tag.id" />{{ tag.name }}</label><span v-if="!metadata(rule).tags.length" class="muted">暂无可选标签</span></div></div>
            <p class="muted">同类条件满足任意一个即可；不同分类条件会同时生效。</p>
          </fieldset>
          <div class="rule-numbers">
            <label>简单题数量<input v-model.number="rule.easyCount" type="number" min="0" max="100" step="1" required /></label>
            <label>中等题数量<input v-model.number="rule.mediumCount" type="number" min="0" max="100" step="1" required /></label>
            <label>困难题数量<input v-model.number="rule.hardCount" type="number" min="0" max="100" step="1" required /></label>
            <label>每题分值<input v-model.number="rule.scorePerQuestion" type="number" min="0.01" max="999999.99" step="0.01" required /></label>
          </div>
        </section>
        <button type="button" class="secondary" @click="addRule">+ 添加规则</button>
      </fieldset>

      <div class="rule-summary" aria-label="规则摘要"><span>规则 {{ summary.ruleCount }} 条</span><span>本次新增 {{ summary.totalRequested }} 题</span><span>预计总分 {{ summary.estimatedTotalScore }} 分</span></div>
      <p v-if="summary.totalRequested > 100" class="error-message">本次新增题数不能超过 100；考试已有题数也由服务端核对。</p>
      <div class="actions"><button type="button" class="secondary" :disabled="busy || loadingBanks || !banks.length || summary.totalRequested > 100" @click="diagnose">{{ state.diagnosing ? '检查中…' : '检查候选' }}</button><button type="button" class="primary" :disabled="busy || !state.diagnoseResult?.feasible" @click="generatePreview">{{ state.previewing ? '生成中…' : '生成试卷预览' }}</button></div>

      <section v-if="state.diagnoseResult" class="rule-diagnosis" aria-label="候选诊断">
        <h3>候选诊断</h3>
        <div class="rule-summary"><span>需要 {{ state.diagnoseResult.totalRequested }} 题</span><span>找到 {{ state.diagnoseResult.matchedCount }} 题</span><span>还缺 {{ state.diagnoseResult.totalRequested - state.diagnoseResult.matchedCount }} 题</span><span>预计 {{ state.diagnoseResult.estimatedTotalScore }} 分</span><strong>{{ state.diagnoseResult.feasible ? '可以生成完整试卷' : '暂时无法生成完整试卷' }}</strong></div>
        <p v-if="!state.diagnoseResult.feasible && !anyLocalShortage" class="error-message">规则之间的候选题重叠，无法组成不重复的试卷。请调整题型、分类、难度或题量。</p>
        <p v-else-if="!state.diagnoseResult.feasible" class="error-message">下列题型和难度存在缺口。请补充具体主题关键词、放宽分类或难度条件，或减少题量。</p>
        <p v-for="unmet in state.diagnoseResult.unmetDemands || []" :key="unmet" class="rule-shortage">全局去重后：{{ unmet }}</p>
        <div v-for="result in state.diagnoseResult.rules" :key="result.ruleIndex" class="rule-diagnosis-row">
          <strong>规则 {{ result.ruleIndex + 1 }} · {{ result.questionType === 'SINGLE_CHOICE' ? '单选题' : '简答题' }}</strong>
          <span v-for="level in ['easy', 'medium', 'hard']" :key="level" :class="{ 'rule-shortage': result[level].candidateCount < result[level].requestedCount }">{{ difficultyNames[level.toUpperCase()] }}：需要 {{ result[level].requestedCount }} 题，找到 {{ result[level].candidateCount }} 题，缺 {{ Math.max(0, result[level].requestedCount - result[level].candidateCount) }} 题</span>
        </div>
      </section>
    </template>

    <template v-else-if="state.previewResult">
      <div class="section-head"><div><h3>试卷预览</h3><p class="muted">本次 {{ state.previewResult.totalRequested }} 题 · 预计 {{ state.previewResult.estimatedTotalScore }} 分</p></div><button type="button" class="ghost" :disabled="busy" @click="backToRules">返回修改规则</button></div>
      <p class="muted">返回修改规则后需重新检查候选并生成预览。</p>
      <article v-for="(item, index) in state.previewResult.selectedQuestions" :key="`${index}-${item.bankItemId}`" class="rule-preview-question question-card">
        <div class="actions"><strong>{{ index + 1 }}.</strong><span class="badge">规则 {{ item.ruleIndex + 1 }}</span><span class="badge">{{ item.questionType === 'SINGLE_CHOICE' ? '单选题' : '简答题' }}</span><span class="badge">{{ difficultyNames[item.difficulty] || item.difficulty }}</span><strong>{{ item.score }} 分</strong></div>
        <h4 v-if="item.title">{{ item.title }}</h4><p class="preserve">{{ item.content }}</p>
        <div v-for="option in item.options || []" :key="option.key" class="option-row"><strong>{{ option.key }}.</strong> {{ option.text }}</div>
        <p class="answer-preview preserve"><strong>{{ item.questionType === 'SINGLE_CHOICE' ? '正确答案' : '参考答案' }}：</strong>{{ item.referenceAnswer || '—' }}</p>
        <p class="muted">章节：{{ chapterPath(item.chapterId, selectedMetadata(item).chapters) }} · 知识点：{{ compactNames(item.knowledgePointIds || [], selectedMetadata(item).knowledgePoints, 5) }} · 标签：{{ compactNames(item.tagIds || [], selectedMetadata(item).tags, 5) }}</p>
      </article>
      <div class="actions rule-preview-actions"><button type="button" class="secondary" :disabled="busy" @click="regenerate">{{ state.previewing ? '重新生成中…' : '重新生成' }}</button><button type="button" class="primary" :disabled="busy || !state.previewResult.selectedQuestions.length" @click="confirmAdd">{{ state.confirming ? '加入中…' : '确认加入试卷' }}</button></div>
    </template>
  </div>
</template>

<style scoped>
.rule-paper-composer { gap: 14px; }
.rule-paper-composer p { margin: 0; }
.rule-edit-fields { display: grid; gap: 14px; padding: 0; border: 0; min-width: 0; }
.rule-edit-fields > button { justify-self: start; }
.rule-block { display: grid; gap: 13px; padding: 14px; border: 1px solid #d5deeb; border-radius: 5px; background: #fff; }
.rule-block .section-head { margin: 0; }
.rule-block h3, .rule-diagnosis h3, .rule-paper-composer h4 { margin: 0; }
.rule-numbers { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }
.rule-summary { display: flex; flex-wrap: wrap; gap: 8px 18px; padding: 10px 12px; border: 1px solid #e5e7eb; border-radius: 4px; background: #f7f8fa; }
.rule-diagnosis { display: grid; gap: 10px; padding: 12px; border: 1px solid #d5deeb; border-radius: 4px; }
.rule-diagnosis-row { display: flex; flex-wrap: wrap; gap: 8px 18px; padding: 8px 0; border-top: 1px solid #e5e7eb; }
.rule-shortage { color: #a53535; font-weight: 700; }
.rule-preview-question { padding: 12px; border: 1px solid #e5e7eb; border-radius: 4px; }
.rule-preview-question > * + * { margin-top: 8px; }
.rule-preview-question .actions { flex-wrap: wrap; }
.rule-preview-actions { justify-content: flex-end; flex-wrap: wrap; }
@media (max-width: 760px) { .rule-numbers { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 430px) { .rule-numbers { grid-template-columns: 1fr; } }
</style>
