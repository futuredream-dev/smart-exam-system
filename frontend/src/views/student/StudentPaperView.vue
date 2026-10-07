<script setup>
import { computed, onMounted, onBeforeUnmount, reactive, ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { studentApi, formatTime } from '../../api/student'
import TeacherModal from '../../components/TeacherModal.vue'

const route = useRoute(), router = useRouter()
const paper = ref(), drafts = reactive({}), savedAnswers = reactive({}), saving = reactive({})
const saveErrors = reactive({}), savedOnce = reactive({})
const error = ref(''), busy = ref(false), loading = ref(true), submitOpen = ref(false)
const activeQuestionId = ref(null), displayNow = ref(Date.now())
let timer, displayTimer
let examActive = false
let lastHiddenAt = 0

const questions = computed(() => paper.value?.questions || [])
const hasAnswer = value => String(value ?? '').trim().length > 0
const answeredCount = computed(() => questions.value.filter(q => hasAnswer(drafts[q.id])).length)
const unsavedCount = computed(() => questions.value.filter(q => drafts[q.id] !== savedAnswers[q.id]).length)
const savingCount = computed(() => questions.value.filter(q => saving[q.id]).length)
const failedCount = computed(() => questions.value.filter(q => saveErrors[q.id]).length)
const remainingSeconds = computed(() => {
  const end = new Date(paper.value?.endTime).getTime()
  return Number.isFinite(end) ? Math.max(0, Math.ceil((end - displayNow.value) / 1000)) : null
})
const remainingTime = computed(() => {
  if (remainingSeconds.value === null) return ''
  const seconds = remainingSeconds.value
  return [Math.floor(seconds / 3600), Math.floor(seconds / 60) % 60, seconds % 60].map(value => String(value).padStart(2, '0')).join(':')
})
const saveSummary = computed(() => savingCount.value ? '正在保存 ' + savingCount.value + ' 题'
  : failedCount.value ? failedCount.value + ' 题保存失败，请重试'
  : unsavedCount.value ? unsavedCount.value + ' 题修改尚未保存'
  : questions.value.some(q => savedOnce[q.id] || hasAnswer(savedAnswers[q.id])) ? '当前答案已保存' : '请逐题保存答案')

function saveFeedback(q) {
  if (saving[q.id]) return { label: '保存中…', tone: 'info' }
  if (saveErrors[q.id]) return { label: '保存失败，请重试：' + saveErrors[q.id], tone: 'danger' }
  if (drafts[q.id] !== savedAnswers[q.id]) return { label: '有修改，尚未保存', tone: 'warning' }
  if (savedOnce[q.id] || hasAnswer(savedAnswers[q.id])) return { label: '已保存', tone: 'success' }
  return { label: '未作答', tone: 'neutral' }
}
function goToQuestion(id) {
  activeQuestionId.value = id
  const heading = document.getElementById('student-question-heading-' + id)
  heading?.scrollIntoView({ block: 'start' })
  heading?.focus({ preventScroll: true })
}
function eventId() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  return Date.now().toString(36) + '-' + Math.random().toString(36).slice(2)
}
function activePaper() {
  if (!examActive || !paper.value) return false
  if (Date.now() >= new Date(paper.value.endTime).getTime()) { examActive = false; return false }
  return true
}
function reportEvent(eventType) {
  if (!activePaper()) return
  // Preserve existing event reporting; it never blocks answering or navigation.
  void studentApi.reportExamBehaviorEvent(paper.value.submissionId, eventType, eventId()).catch(() => {})
}
function onVisibilityChange() {
  if (document.visibilityState !== 'hidden' || !activePaper()) return
  const now = Date.now()
  if (now - lastHiddenAt < 1500) return
  lastHiddenAt = now
  reportEvent('TAB_HIDDEN')
}
onBeforeRouteLeave(() => {
  if (Date.now() - lastHiddenAt >= 1500) reportEvent('PAGE_LEAVE')
})
async function load() {
  try {
    paper.value = (await studentApi.paper(route.params.submissionId)).data
    paper.value.questions.forEach(q => { drafts[q.id] = q.answerContent || ''; savedAnswers[q.id] = drafts[q.id] })
    activeQuestionId.value = paper.value.questions[0]?.id ?? null
    examActive = Date.now() < new Date(paper.value.endTime).getTime()
    // Keep the existing expiry/navigation check separate from the display clock.
    timer = setInterval(() => {
      if (paper.value && Date.now() >= new Date(paper.value.endTime).getTime()) {
        examActive = false
        clearInterval(timer)
        router.replace('/student')
      }
    }, 5000)
  } catch (e) { error.value = e.message || '加载试卷失败，请刷新重试' }
  finally { loading.value = false }
}
onMounted(() => {
  document.addEventListener('visibilitychange', onVisibilityChange)
  displayTimer = setInterval(() => { displayNow.value = Date.now() }, 1000)
  void load()
})
onBeforeUnmount(() => {
  examActive = false
  document.removeEventListener('visibilitychange', onVisibilityChange)
  clearInterval(timer)
  clearInterval(displayTimer)
})
async function save(q) {
  const sentAnswer = drafts[q.id]
  saving[q.id] = true; saveErrors[q.id] = ''; error.value = ''
  try {
    await studentApi.save(paper.value.submissionId, q.id, sentAnswer)
    savedAnswers[q.id] = sentAnswer
    savedOnce[q.id] = true
  } catch (e) { saveErrors[q.id] = e.message || '请检查网络后重新保存' }
  finally { saving[q.id] = false }
}
function askSubmit() { error.value = ''; submitOpen.value = true }
async function submit() {
  if (busy.value || savingCount.value) return
  busy.value = true; error.value = ''
  try {
    await studentApi.submit(paper.value.submissionId)
    examActive = false
    sessionStorage.setItem('studentSubmitSuccess', JSON.stringify({ examId: paper.value.examId }))
    router.replace('/student')
  } catch (e) { error.value = e.message }
  finally { busy.value = false }
}
</script>

<template>
  <div class="student-paper">
    <header v-if="paper" class="student-paper-header">
      <div class="student-paper-heading"><div><h1>{{ paper.title }}</h1><p class="muted">考试结束 {{ formatTime(paper.endTime) }}，请逐题保存答案。</p></div><button class="primary" :disabled="busy" @click="askSubmit">{{ busy ? '正在交卷…' : '正式交卷' }}</button></div>
      <dl class="student-paper-summary">
        <div v-if="remainingSeconds !== null"><dt>剩余时间</dt><dd class="student-remaining-time">{{ remainingTime }}</dd></div>
        <div><dt>已作答</dt><dd>{{ answeredCount }} <span>/ {{ questions.length }} 题</span></dd></div>
        <div class="student-save-summary"><dt>保存状态</dt><dd :data-tone="failedCount ? 'danger' : unsavedCount ? 'warning' : 'neutral'" role="status">{{ saveSummary }}</dd></div>
      </dl>
    </header>
    <p v-if="error && !submitOpen" class="error-message" role="alert">{{ error }}</p>
    <p v-if="loading" class="loading-state" role="status">正在加载试卷…</p>
    <template v-if="paper">
      <nav class="student-question-nav" aria-label="答题导航"><span>题目导航</span><div><button v-for="(q, index) in questions" :key="q.id" type="button" :class="{ answered: hasAnswer(drafts[q.id]), current: activeQuestionId === q.id }" :aria-current="activeQuestionId === q.id ? 'location' : undefined" :aria-label="'跳至第 ' + (index + 1) + ' 题，' + (hasAnswer(drafts[q.id]) ? '已作答' : '未作答')" @click="goToQuestion(q.id)">{{ index + 1 }}</button></div><small>实心数字为已作答</small></nav>
      <div class="surface student-paper-sheet">
        <section v-for="(q, index) in questions" :key="q.id" class="student-question" :data-question-id="q.id" :aria-labelledby="'student-question-heading-' + q.id" @focusin="activeQuestionId = q.id">
          <header class="student-question-head"><h2 :id="'student-question-heading-' + q.id" tabindex="-1">第 {{ index + 1 }} 题</h2><span>{{ q.questionType === 'SINGLE_CHOICE' ? '单选题' : '简答题' }}</span><span class="student-question-points">{{ q.score }} 分</span></header>
          <p class="student-question-content preserve">{{ q.content }}</p>
          <div class="student-answer-area">
            <fieldset v-if="q.questionType === 'SINGLE_CHOICE'" class="student-choice-options"><legend class="student-visually-hidden">第 {{ index + 1 }} 题选择答案</legend><label v-for="option in q.options" :key="option.key" class="answer-option"><input v-model="drafts[q.id]" type="radio" :name="'q' + q.id" :value="option.key" :disabled="busy"><strong>{{ option.key }}</strong><span>{{ option.text }}</span></label></fieldset>
            <label v-else class="student-written-answer"><span>我的答案</span><textarea v-model="drafts[q.id]" rows="7" :aria-label="'第 ' + (index + 1) + ' 题答案'" placeholder="请输入你的答案" :disabled="busy"></textarea></label>
          </div>
          <footer class="student-question-save"><button class="secondary" :disabled="saving[q.id] || busy" @click="save(q)">{{ saving[q.id] ? '保存中…' : '保存答案' }}</button><p :data-tone="saveFeedback(q).tone" role="status">{{ saveFeedback(q).label }}</p></footer>
        </section>
      </div>
      <footer class="student-paper-footer"><p class="muted">交卷前，请检查每题的保存状态。</p><button class="secondary" :disabled="busy" @click="askSubmit">正式交卷</button></footer>
    </template>
    <TeacherModal v-if="submitOpen && paper" title="确认交卷" size="confirm" :busy="busy" @close="submitOpen = false">
      <div class="confirmation-copy"><p class="student-submit-exam"><strong>{{ paper.title }}</strong></p><p v-if="answeredCount < questions.length">还有 <strong>{{ questions.length - answeredCount }}</strong> 题未作答。</p><p v-else>已作答 {{ answeredCount }} / {{ questions.length }} 题。</p><p v-if="unsavedCount" class="student-submit-warning">还有 {{ unsavedCount }} 题修改尚未保存。交卷不会自动保存这些修改，请取消并逐题保存后再交卷。</p><p v-if="savingCount" class="student-submit-warning">仍有 {{ savingCount }} 题正在保存，请先检查保存结果。</p><p>正式交卷后不能继续作答或修改答案。</p><p v-if="error" class="error-message" role="alert">{{ error }}</p></div>
      <template #footer><button class="secondary" :disabled="busy" @click="submitOpen = false">继续检查</button><button class="primary" :disabled="busy || savingCount > 0" @click="submit">{{ busy ? '正在交卷…' : '确认交卷' }}</button></template>
    </TeacherModal>
  </div>
</template>
