<script setup>
import { computed, onMounted, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { studentApi, formatTime } from '../../api/student'
import { authState } from '../../auth/auth'
import { createAnswerSaveQueue, readDraft, writeDraft, clearDraft } from '../../utils/studentAnswerDraft'
import { remainingMillis } from '../../utils/examClock'
import { safeReturnPath } from '../../utils/returnPath'
import TeacherModal from '../../components/TeacherModal.vue'

const route = useRoute(), router = useRouter(), ownerId = authState.currentUser?.id
const paper = ref(), drafts = reactive({}), savedAnswers = reactive({}), queueState = ref({})
const error = ref(''), busy = ref(false), loading = ref(true), submitOpen = ref(false), activeQuestionId = ref(null)
const recovery = ref(null), storageNotice = ref(''), sessionExpired = ref(false), expired = ref(false), terminal = ref(false), receipt = ref(''), statusUncertain = ref(false)
const leaveOpen = ref(false), checking = ref(false), elapsed = ref(0)
let disposed = false, displayTimer, deadlineTimer, leaveResolver, anchorAt = 0, lastServerTime = -Infinity, lastHiddenAt = -Infinity
const questions = computed(() => paper.value?.questions || [])
const hasAnswer = value => String(value ?? '').trim().length > 0
const answeredCount = computed(() => questions.value.filter(q => hasAnswer(drafts[q.id])).length)
function isDirty(id) { const row = queueState.value[id]; return Boolean(row && row.savedRevision < row.revision) || drafts[id] !== savedAnswers[id] }
const unsavedCount = computed(() => questions.value.filter(q => isDirty(q.id)).length)
const savingCount = computed(() => Object.values(queueState.value).filter(row => row.inFlight).length)
const failedCount = computed(() => Object.values(queueState.value).filter(row => row.status === 'failed').length)
const locked = computed(() => busy.value || expired.value || terminal.value || sessionExpired.value || statusUncertain.value || !!recovery.value)
const remainingSeconds = computed(() => paper.value ? Math.ceil(remainingMillis(paper.value.serverNow, paper.value.deadlineAt, elapsed.value) / 1000) : null)
const remainingTime = computed(() => remainingSeconds.value === null ? '' : [Math.floor(remainingSeconds.value / 3600), Math.floor(remainingSeconds.value / 60) % 60, remainingSeconds.value % 60].map(value => String(value).padStart(2, '0')).join(':'))
const saveSummary = computed(() => savingCount.value ? `正在保存 ${savingCount.value} 题` : failedCount.value ? `${failedCount.value} 题保存失败，请重试` : unsavedCount.value ? `${unsavedCount.value} 题修改尚未保存` : '当前答案已保存')
function sameOwner() { return String(authState.currentUser?.id ?? authState.draftOwner ?? '') === String(ownerId) }
function persistDraft() {
  if (!paper.value || disposed || terminal.value || !sameOwner()) return
  const answers = {}
  for (const q of questions.value) if (isDirty(q.id)) {
    const row = queueState.value[q.id]
    answers[q.id] = { value: drafts[q.id], revision: row?.revision || 1, uncertain: Boolean(row?.inFlight || ['NETWORK_ERROR', 'REQUEST_TIMEOUT'].includes(row?.error?.errorCode)) }
  }
  // Do not discard a recovery candidate until the student chooses whether to restore it.
  if (recovery.value) return
  const ok = Object.keys(answers).length ? writeDraft(ownerId, paper.value.submissionId, answers) : clearDraft(ownerId, paper.value.submissionId)
  if (!ok) storageNotice.value = '浏览器无法保存会话草稿；仍可作答和保存到服务器，刷新或关闭前请确认全部保存成功。'
}
function syncClock(value, authoritative = false) {
  const stamp = Date.parse(value?.serverNow)
  if (!Number.isFinite(stamp) || stamp < lastServerTime || !paper.value) return false
  const arrivedAt = performance.now()
  // serverNow is sampled when the response is built, after request processing.
  // Ordinary late responses cannot extend a countdown; an explicit active status can correct it.
  const projected = !authoritative && Number.isFinite(lastServerTime) && value.deadlineAt === paper.value.deadlineAt
    ? Date.parse(paper.value.serverNow) + Math.max(0, arrivedAt - anchorAt) : stamp
  lastServerTime = stamp; paper.value.serverNow = new Date(Math.max(stamp, projected)).toISOString(); paper.value.deadlineAt = value.deadlineAt
  anchorAt = arrivedAt; elapsed.value = 0
  return true
}
const queue = createAnswerSaveQueue({
  save: async (id, value) => {
    if (disposed || expired.value || terminal.value || !sameOwner() || sessionExpired.value) throw { errorCode: 'CONFLICT', message: '当前不能继续保存，请确认答卷状态' }
    try {
      const result = await studentApi.save(paper.value.submissionId, id, value)
      if (!disposed && sameOwner() && !expired.value) syncClock(result.data)
      return result
    } catch (failure) {
      if (failure.errorCode === 'UNAUTHORIZED' || failure.httpStatus === 401) { sessionExpired.value = true; queue.pause() }
      else if (['FORBIDDEN', 'CONFLICT'].includes(failure.errorCode) || [403, 409].includes(failure.httpStatus)) { expired.value = true; queue.pause(); void confirmServerStatus() }
      throw failure
    }
  },
  onChange: state => {
    queueState.value = state
    for (const row of Object.values(state)) if (row.savedRevision > 0) savedAnswers[row.id] = row.savedValue
    persistDraft()
  },
})
function editAnswer(id, value) {
  if (locked.value || !paper.value || !questions.value.some(q => q.id === id)) return
  drafts[id] = value; queue.edit(id, value)
}
function save(q) { if (!locked.value) { if (!queue.getState(q.id) && drafts[q.id] !== savedAnswers[q.id]) queue.edit(q.id, drafts[q.id]); queue.retry(q.id) } }
function saveFeedback(q) {
  const row = queueState.value[q.id]
  if (row?.inFlight) return { label: '保存中…', tone: 'info' }
  if (row?.status === 'failed') return { label: '保存失败，请重试：' + (row.error?.message || '网络异常'), tone: 'danger' }
  if (isDirty(q.id)) return { label: row?.error ? '暂时无法保存，正在等待重试' : '有修改，等待自动保存', tone: 'warning' }
  return { label: hasAnswer(savedAnswers[q.id]) || row ? '已保存' : '未作答', tone: 'success' }
}
function restoreLocalDraft() {
  const candidate = recovery.value; recovery.value = null
  if (!candidate || expired.value || terminal.value || sessionExpired.value) return
  for (const [id, entry] of Object.entries(candidate.answers)) { const q = questions.value.find(q => String(q.id) === id); if (q && (entry.value !== savedAnswers[q.id] || entry.uncertain === true)) editAnswer(q.id, entry.value) }
  persistDraft()
}
function discardLocalDraft() { recovery.value = null; if (paper.value) clearDraft(ownerId, paper.value.submissionId) }
function goToQuestion(id) { activeQuestionId.value = id; const heading = document.getElementById('student-question-heading-' + id); heading?.scrollIntoView({ block: 'start' }); heading?.focus({ preventScroll: true }) }
function eventId() { return globalThis.crypto?.randomUUID?.() || Date.now().toString(36) + '-' + Math.random().toString(36).slice(2) }
function reportEvent(type) { if (paper.value && !expired.value && !terminal.value && !sessionExpired.value) void studentApi.reportExamBehaviorEvent(paper.value.submissionId, type, eventId()).catch(() => {}) }
async function load() {
  loading.value = true
  try {
    const value = (await studentApi.paper(route.params.submissionId)).data
    if (disposed || !sameOwner()) return
    if (value.status !== 'IN_PROGRESS' || String(value.submissionId) !== String(route.params.submissionId)) throw { errorCode: 'CONFLICT', message: '答卷已提交或不可继续作答' }
    paper.value = value
    for (const q of value.questions) { drafts[q.id] = q.answerContent ?? ''; savedAnswers[q.id] = drafts[q.id] }
    activeQuestionId.value = value.questions[0]?.id ?? null; syncClock(value)
    const local = readDraft(ownerId, value.submissionId)
    if (local && value.questions.some(q => local.answers[q.id] && (local.answers[q.id].value !== savedAnswers[q.id] || local.answers[q.id].uncertain === true))) recovery.value = local
    else clearDraft(ownerId, value.submissionId)
    await checkDeadline()
  } catch (failure) {
    error.value = failure.message || '加载试卷失败，请刷新重试'
    if (failure.errorCode === 'UNAUTHORIZED') sessionExpired.value = true
    if (failure.errorCode === 'CONFLICT') { expired.value = true; clearDraft(ownerId, route.params.submissionId) }
  } finally { loading.value = false }
}
async function confirmServerStatus(allowResume = true) {
  if (checking.value || disposed || !paper.value || !sameOwner() || sessionExpired.value) return
  checking.value = true
  try {
    const value = (await studentApi.exam(paper.value.examId)).data
    if (disposed || !sameOwner()) return
    if (value.submissionStatus === 'IN_PROGRESS' && allowResume === true
      && remainingMillis(value.serverNow, value.deadlineAt, 0) > 0 && syncClock(value, true)) {
      expired.value = false; statusUncertain.value = false; receipt.value = ''; queue.resume()
    } else if (value.submissionStatus && value.submissionStatus !== 'IN_PROGRESS') {
      error.value = ''
      terminal.value = true; statusUncertain.value = false; submitOpen.value = false; queue.dispose(); clearDraft(ownerId, paper.value.submissionId)
      receipt.value = value.submissionStatus === 'AUTO_SUBMITTED' ? '已自动交卷。' : '答卷已提交。'
    } else receipt.value = '截止状态待确认，请刷新状态；未确认保存的答案不会在截止后补写。'
  } catch (failure) {
    if (failure.errorCode === 'UNAUTHORIZED') sessionExpired.value = true
    receipt.value = '暂时无法确认交卷状态，请检查网络后刷新状态。'
  } finally { checking.value = false }
}
async function checkDeadline(force = false) {
  if (!paper.value || terminal.value) return
  elapsed.value = Math.max(0, performance.now() - anchorAt)
  if (force || remainingMillis(paper.value.serverNow, paper.value.deadlineAt, elapsed.value) <= 0) { expired.value = true; submitOpen.value = false; queue.pause(); persistDraft(); await confirmServerStatus() }
}
async function refreshStatus() {
  if (expired.value || terminal.value) return confirmServerStatus()
  if (!paper.value || disposed) return
  try { const value = (await studentApi.paper(paper.value.submissionId)).data; if (!disposed && sameOwner()) { syncClock(value); await checkDeadline() } }
  catch (failure) { if (failure.errorCode === 'CONFLICT') await checkDeadline(true); else if (failure.errorCode === 'UNAUTHORIZED') { sessionExpired.value = true; queue.pause() } else error.value = failure.message || '刷新状态失败' }
}
function onVisibilityChange() {
  if (document.visibilityState === 'visible') { void refreshStatus(); return }
  const now = performance.now(); if (now - lastHiddenAt >= 1500) { lastHiddenAt = now; reportEvent('TAB_HIDDEN') }
}
function beforeUnload(event) { if (unsavedCount.value && !terminal.value && !expired.value) { persistDraft(); event.preventDefault(); event.returnValue = '' } }
function resolveLeave(confirmed) { leaveOpen.value = false; const resolve = leaveResolver; leaveResolver = null; if (confirmed) reportEvent('PAGE_LEAVE'); resolve?.(confirmed) }
onBeforeRouteLeave(() => {
  if (!unsavedCount.value || terminal.value || expired.value || sessionExpired.value || !authState.token) { reportEvent('PAGE_LEAVE'); return true }
  if (leaveResolver) return false
  leaveOpen.value = true; return new Promise(resolve => { leaveResolver = resolve })
})
const stopAuthWatch = watch(() => [authState.currentUser?.id, authState.token], () => { if (!sameOwner() || !authState.token) { sessionExpired.value = true; queue.pause() } }, { flush: 'sync' })
onMounted(() => {
  document.addEventListener('visibilitychange', onVisibilityChange); window.addEventListener('beforeunload', beforeUnload)
  displayTimer = setInterval(() => { elapsed.value = Math.max(0, performance.now() - anchorAt) }, 1000)
  deadlineTimer = setInterval(() => { void checkDeadline() }, 1000); void load()
})
onBeforeUnmount(() => { persistDraft(); disposed = true; queue.dispose(); stopAuthWatch(); resolveLeave(false); clearInterval(displayTimer); clearInterval(deadlineTimer); document.removeEventListener('visibilitychange', onVisibilityChange); window.removeEventListener('beforeunload', beforeUnload) })
function askSubmit() { if (!locked.value) { error.value = ''; submitOpen.value = true } }
function requestLogin() { queue.pause(); persistDraft(); void router.push({ path: '/login', query: { returnTo: safeReturnPath(route.fullPath) || '/student' } }) }
async function submit() {
  if (locked.value || !paper.value) return
  busy.value = true; error.value = ''; let submitSent = false
  try {
    await queue.flush()
    if (unsavedCount.value || expired.value || sessionExpired.value || !sameOwner()) throw Error('仍有答案未保存，请检查保存状态后重试')
    submitSent = true; await studentApi.submit(paper.value.submissionId)
    if (disposed || !sameOwner()) return
    terminal.value = true; queue.dispose(); clearDraft(ownerId, paper.value.submissionId); submitOpen.value = false
    try { sessionStorage.setItem('studentSubmitSuccess', JSON.stringify({ examId: paper.value.examId })) } catch {}
    await router.replace('/student?submitted=' + paper.value.examId)
  } catch (failure) {
    error.value = failure.message || '交卷结果未确认，请先刷新状态'
    if (failure.errorCode === 'UNAUTHORIZED') { sessionExpired.value = true; queue.pause() }
    if (failure.errorCode === 'CONFLICT' || (submitSent && ['NETWORK_ERROR', 'REQUEST_TIMEOUT'].includes(failure.errorCode))) {
      statusUncertain.value = true; queue.pause(); submitOpen.value = false; await confirmServerStatus(true)
    }
  } finally { busy.value = false }
}
</script>


<template>
  <div class="student-paper">
    <header v-if="paper" class="student-paper-header">
      <div class="student-paper-heading"><div><h1>{{ paper.title }}</h1><p class="muted">考试结束 {{ formatTime(paper.endTime) }}，停止输入 1 秒后自动保存。</p></div><button class="primary" :disabled="locked" @click="askSubmit">{{ busy ? '正在保存并交卷…' : '正式交卷' }}</button></div>
      <dl class="student-paper-summary">
        <div v-if="remainingSeconds !== null"><dt>剩余时间</dt><dd class="student-remaining-time">{{ remainingTime }}</dd></div>
        <div><dt>已作答</dt><dd>{{ answeredCount }} <span>/ {{ questions.length }} 题</span></dd></div>
        <div class="student-save-summary"><dt>保存状态</dt><dd :data-tone="failedCount ? 'danger' : unsavedCount ? 'warning' : 'neutral'" role="status">{{ saveSummary }}</dd></div>
      </dl>
    </header>
    <p v-if="error && !submitOpen" class="error-message" role="alert">{{ error }}</p>
    <p v-if="storageNotice" class="student-submit-warning" role="alert">{{ storageNotice }}</p>
    <div v-if="sessionExpired" class="surface" role="alert"><p>登录已过期，自动保存已暂停。会话草稿已保留，请使用同一账号重新登录后确认恢复。</p><p v-if="storageNotice">本地草稿存储不可用，请先复制尚未保存的答案。</p><button class="primary" @click="requestLogin">重新登录</button></div>
    <div v-if="receipt" class="surface" role="status"><p>{{ receipt }}</p><p v-if="unsavedCount">仍有 {{ unsavedCount }} 题未确认保存，截止后不会补写答案。</p><button class="secondary" :disabled="checking" @click="confirmServerStatus(true)">{{ checking ? '正在确认…' : '刷新交卷状态' }}</button> <RouterLink class="secondary" to="/student">返回我的考试</RouterLink></div>
    <p v-if="loading" class="loading-state" role="status">正在加载试卷…</p>
    <template v-if="paper">
      <nav class="student-question-nav" aria-label="答题导航"><span>题目导航</span><div><button v-for="(q, index) in questions" :key="q.id" type="button" :class="{ answered: hasAnswer(drafts[q.id]), current: activeQuestionId === q.id }" :aria-current="activeQuestionId === q.id ? 'location' : undefined" :aria-label="'跳至第 ' + (index + 1) + ' 题，' + (hasAnswer(drafts[q.id]) ? '已作答' : '未作答')" @click="goToQuestion(q.id)">{{ index + 1 }}</button></div><small>实心数字为已作答</small></nav>
      <div class="surface student-paper-sheet">
        <section v-for="(q, index) in questions" :key="q.id" class="student-question" :data-question-id="q.id" :aria-labelledby="'student-question-heading-' + q.id" @focusin="activeQuestionId = q.id">
          <header class="student-question-head"><h2 :id="'student-question-heading-' + q.id" tabindex="-1">第 {{ index + 1 }} 题</h2><span>{{ q.questionType === 'SINGLE_CHOICE' ? '单选题' : '简答题' }}</span><span class="student-question-points">{{ q.score }} 分</span></header>
          <p class="student-question-content preserve">{{ q.content }}</p>
          <div class="student-answer-area">
            <fieldset v-if="q.questionType === 'SINGLE_CHOICE'" class="student-choice-options"><legend class="student-visually-hidden">第 {{ index + 1 }} 题选择答案</legend><label v-for="option in q.options" :key="option.key" class="answer-option"><input :checked="drafts[q.id] === option.key" type="radio" :name="'q' + q.id" :value="option.key" :disabled="locked" @change="editAnswer(q.id, option.key)"><strong>{{ option.key }}</strong><span>{{ option.text }}</span></label></fieldset>
            <label v-else class="student-written-answer"><span>我的答案</span><textarea :value="drafts[q.id]" rows="7" :aria-label="'第 ' + (index + 1) + ' 题答案'" placeholder="请输入你的答案" :disabled="locked" @input="editAnswer(q.id, $event.target.value)"></textarea></label>
          </div>
          <footer class="student-question-save"><button class="secondary" :disabled="queueState[q.id]?.inFlight || locked" @click="save(q)">{{ queueState[q.id]?.inFlight ? '保存中…' : '立即保存 / 重试' }}</button><p :data-tone="saveFeedback(q).tone" role="status">{{ saveFeedback(q).label }}</p></footer>
        </section>
      </div>
      <footer class="student-paper-footer"><p class="muted">交卷时将等待全部最新答案保存成功。</p><button class="secondary" :disabled="locked" @click="askSubmit">正式交卷</button></footer>
    </template>
    <TeacherModal v-if="submitOpen && paper" title="确认交卷" size="confirm" :busy="busy" @close="submitOpen = false">
      <div class="confirmation-copy"><p class="student-submit-exam"><strong>{{ paper.title }}</strong></p><p v-if="answeredCount < questions.length">还有 <strong>{{ questions.length - answeredCount }}</strong> 题未作答。</p><p v-else>已作答 {{ answeredCount }} / {{ questions.length }} 题。</p><p v-if="unsavedCount" class="student-submit-warning">将先保存 {{ unsavedCount }} 题的最新修改，全部成功后才交卷。失败会留在本页，截止后无法补写。</p><p>正式交卷后不能继续作答或修改答案。</p><p v-if="error" class="error-message" role="alert">{{ error }}</p></div>
      <template #footer><button class="secondary" :disabled="busy" @click="submitOpen = false">继续检查</button><button class="primary" :disabled="busy || expired || sessionExpired" @click="submit">{{ busy ? '正在保存并交卷…' : '确认交卷' }}</button></template>
    </TeacherModal>
    <TeacherModal v-if="recovery" title="发现会话草稿" size="confirm" @close="discardLocalDraft"><p>已确认这份答卷仍可作答。浏览器中有与服务器不同的草稿，是否恢复并保存？</p><template #footer><button class="secondary" @click="discardLocalDraft">使用服务器答案</button><button class="primary" @click="restoreLocalDraft">恢复会话草稿</button></template></TeacherModal>
    <TeacherModal v-if="leaveOpen" title="还有答案未保存" size="confirm" @close="resolveLeave(false)"><p>仍有 {{ unsavedCount }} 题修改尚未保存到服务器。会话草稿仅保留在本标签页，关闭前请确认保存结果。</p><template #footer><button class="secondary" @click="resolveLeave(true)">确认离开</button><button class="primary" @click="resolveLeave(false)">留在本页</button></template></TeacherModal>
  </div>
</template>
