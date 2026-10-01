<script setup>
import { computed, ref, watch } from 'vue'
import { teacherApi } from '../api/teacher'
import RulePaperComposer from './RulePaperComposer.vue'
import { copyRules, createAiRule, createRequestGate, draftFromMappings } from '../utils/aiPaperComposer'

const props = defineProps({ examId: [Number, String], courseId: [Number, String], initialRequirement: { type: String, default: '' } })
const emit = defineEmits(['saved', 'switch-to-rule', 'busy-change', 'requirement-change'])
const requirement = ref(props.initialRequirement)
const interpretation = ref(null)
const choices = ref([])
const rules = ref([createAiRule()])
const stage = ref('INPUT')
const parsing = ref(false)
const childBusy = ref(false)
const error = ref('')
const gate = createRequestGate()
watch([parsing, childBusy], () => emit('busy-change', parsing.value || childBusy.value), { immediate: true })
const mappings = computed(() => interpretation.value?.mappings || [])
const intent = computed(() => interpretation.value?.intent)
const statuses = { MATCHED: '已匹配', AMBIGUOUS: '需选择', UNRESOLVED: '未找到精确元数据' }
const dimensions = { CHAPTER: '章节', KNOWLEDGE_POINT: '知识点', TAG: '标签' }

watch(requirement, () => {
  emit('requirement-change', requirement.value)
  gate.invalidate()
  interpretation.value = null
  choices.value = []
  rules.value = [createAiRule()]
  stage.value = 'INPUT'
  error.value = ''
}, { flush: 'sync' })

async function interpret() {
  if (parsing.value || childBusy.value) return
  error.value = ''
  if (!requirement.value.trim()) { error.value = '请输入组卷要求'; return }
  // Unmount the rule editor and its diagnose/preview state before any new interpretation can arrive.
  stage.value = 'INPUT'
  interpretation.value = null
  choices.value = []
  parsing.value = true
  const revision = gate.capture()
  try {
    const result = (await teacherApi.interpretAiAssembly(props.examId, requirement.value.trim())).data
    if (!gate.current(revision)) return
    interpretation.value = result
    choices.value = (result.mappings || []).map(() => '')
  } catch (e) { if (gate.current(revision)) error.value = e.message || 'AI 解析失败，输入已保留' }
  finally { parsing.value = false }
}

function optionValue(option) { return `${option.bankId}:${option.dimension}:${option.id}` }

function confirmInterpretation() {
  if (!interpretation.value || interpretation.value.needsTopicKeywords) return
  try {
    rules.value = draftFromMappings(mappings.value, choices.value, intent.value)
    stage.value = 'RULES'
    error.value = ''
  } catch (e) { error.value = e.message }
}

function switchToRule() { emit('switch-to-rule', { requirement: requirement.value, rules: copyRules(rules.value) }) }
</script>

<template>
  <div class="ai-paper-composer editor-form">
    <p v-if="error" class="error-message" role="alert">{{ error }}。可切换到规则智能组卷继续。</p>
    <section class="rule-block">
      <h3>组卷要求</h3>
      <label>用自然语言描述考试重点、难度和题型偏好
        <textarea v-model="requirement" rows="4" maxlength="2000" :disabled="childBusy" placeholder="例如：重点考集合和异常处理，难度中等偏上，简答题侧重实际应用" />
      </label>
      <div class="actions"><button type="button" class="secondary" :disabled="parsing || childBusy || !requirement.trim()" @click="interpret">{{ parsing ? '解析中…' : 'AI 解析要求' }}</button><button type="button" class="ghost" :disabled="childBusy" @click="switchToRule">切换到规则智能组卷</button></div>
    </section>

    <section v-if="interpretation" class="rule-diagnosis" aria-label="AI 理解结果">
      <h3>AI 理解结果</h3>
      <p>{{ intent.summary }}</p>
      <p>考试主题关键词：{{ intent.topicKeywords?.join('、') || '未提供' }}</p>
      <p v-if="interpretation.needsTopicKeywords" class="error-message" role="alert">仅凭“第一章”等章节序号无法判断考试内容。请补充具体考试主题，例如 Java基础、集合、异常处理，再重新解析。</p>
      <p>难度倾向：{{ intent.difficultyIntent?.overall || '未指定' }}；题型偏好：{{ intent.questionTypeIntent?.map(item => `${item.questionType}（${item.countHint ?? '数量未指定'}，${item.focus}）`).join('、') || '未指定' }}</p>
      <p class="muted">题数建议：{{ intent.questionCountHint ?? '未指定' }}；总分建议：{{ intent.totalScoreHint ?? '未指定' }}。规则中的题数和每题分值由教师填写。</p>
      <p v-if="intent.unresolvedRequirements?.length" class="muted">尚未确定：{{ intent.unresolvedRequirements.join('、') }}</p>
      <div v-for="(mapping, index) in mappings" :key="index" class="ai-topic-row">
        <strong>{{ mapping.text }}</strong><span class="badge">{{ statuses[mapping.status] || mapping.status }}</span>
        <label>对应真实题库元数据
          <select v-model="choices[index]" :disabled="stage === 'RULES' || childBusy">
            <option value="">暂不选用，稍后手动配置</option>
            <option v-for="option in mapping.options" :key="optionValue(option)" :value="optionValue(option)">{{ option.bankName }} · {{ dimensions[option.dimension] }} · {{ option.name }}</option>
          </select>
        </label>
      </div>
      <p v-if="mappings.some(mapping => mapping.status === 'UNRESOLVED')" class="muted">未找到精确元数据，将按主题关键词检索。</p>
      <p class="muted">章节、知识点和标签只作相关度参考；主动选择后才成为组卷硬条件。</p>
      <div class="actions"><button v-if="stage !== 'RULES'" type="button" class="primary" :disabled="childBusy || interpretation.needsTopicKeywords" @click="confirmInterpretation">确认理解并编辑规则</button><button v-else type="button" class="ghost" :disabled="childBusy" @click="stage = 'INPUT'">返回修改映射</button></div>
    </section>

    <section v-if="stage === 'RULES'" class="ai-rule-section">
      <h3>结构化组卷规则</h3>
      <p class="muted">已带入明确的题型、题数和难度；请确认题库和每题分值。多题库或条件不明确时请手动分配题数。</p>
      <RulePaperComposer :exam-id="examId" :course-id="courseId" :initial-rules="rules" :requirement="requirement" :intent="intent" ai-mode
        @draft-change="rules = $event" @busy-change="childBusy = $event" @saved="emit('saved', $event)" />
    </section>
  </div>
</template>

<style scoped>
.ai-paper-composer { display: grid; gap: 16px; }
.ai-paper-composer textarea { width: 100%; min-height: 110px; resize: vertical; }
.ai-topic-row { display: grid; grid-template-columns: minmax(100px, 1fr) auto minmax(220px, 2fr); gap: 12px; align-items: center; padding: 8px 0; border-top: 1px solid #e5e7eb; }
.ai-topic-row label { margin: 0; }
.ai-rule-section { display: grid; gap: 10px; }
@media (max-width: 760px) { .ai-topic-row { grid-template-columns: 1fr; } }
</style>
