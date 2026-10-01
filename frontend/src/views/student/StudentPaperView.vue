<script setup>
import { onMounted, onBeforeUnmount, reactive, ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { studentApi, formatTime } from '../../api/student'

const route = useRoute(), router = useRouter()
const paper = ref(), drafts = reactive({}), saving = reactive({})
const error = ref(''), notice = ref(''), busy = ref(false), loading = ref(true)
let timer
let examActive = false
let lastHiddenAt = 0

function eventId() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`
}

function activePaper() {
  if (!examActive || !paper.value) return false
  if (Date.now() >= new Date(paper.value.endTime).getTime()) {
    examActive = false
    return false
  }
  return true
}

function reportEvent(eventType) {
  if (!activePaper()) return
  // Event logging never blocks answering, submission, or route navigation.
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
  // A visibility change immediately before navigation is one departure action.
  if (Date.now() - lastHiddenAt >= 1500) reportEvent('PAGE_LEAVE')
})

async function load() {
  try {
    paper.value = (await studentApi.paper(route.params.submissionId)).data
    paper.value.questions.forEach(q => drafts[q.id] = q.answerContent || '')
    examActive = Date.now() < new Date(paper.value.endTime).getTime()
    timer = setInterval(() => {
      if (paper.value && Date.now() >= new Date(paper.value.endTime).getTime()) {
        examActive = false
        clearInterval(timer)
        router.replace('/student')
      }
    }, 5000)
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  document.addEventListener('visibilitychange', onVisibilityChange)
  void load()
})
onBeforeUnmount(() => {
  examActive = false
  document.removeEventListener('visibilitychange', onVisibilityChange)
  clearInterval(timer)
})

async function save(q) {
  saving[q.id] = true
  error.value = ''
  try {
    await studentApi.save(paper.value.submissionId, q.id, drafts[q.id])
    notice.value = `第 ${q.sortOrder} 题已保存`
  } catch (e) {
    error.value = e.message
  } finally {
    saving[q.id] = false
  }
}

async function submit() {
  if (!confirm('正式交卷后不能继续答题，确认交卷吗？')) return
  busy.value = true
  try {
    await studentApi.submit(paper.value.submissionId)
    examActive = false
    sessionStorage.setItem('studentSubmitSuccess', JSON.stringify({ examId: paper.value.examId }))
    router.replace('/student')
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}
</script>
<template><div class="student-paper"><header v-if="paper" class="page-heading student-paper-header"><div><h1>{{paper.title}}</h1><p class="muted">结束时间 {{formatTime(paper.endTime)}} · 每题单独保存</p></div><button class="primary" :disabled="busy" @click="submit">{{busy?'正在交卷…':'正式交卷'}}</button></header><p v-if="error" class="error-message">{{error}}</p><p v-if="notice" class="success-message">{{notice}}</p><p v-if="loading" class="empty-state">正在加载试卷…</p><article v-for="(q,index) in paper?.questions" :key="q.id" class="surface question-card student-question-card"><div class="section-head"><h2 class="student-question-title"><span>第 {{index+1}} 题</span><span :class="['badge',q.questionType]">{{q.questionType==='SINGLE_CHOICE'?'单选题':'简答题'}} · {{q.score}} 分</span></h2><button class="secondary student-save-action" :disabled="saving[q.id]" @click="save(q)">{{saving[q.id]?'保存中…':'保存答案'}}</button></div><p class="preserve">{{q.content}}</p><div class="student-answer-area"><div v-if="q.questionType==='SINGLE_CHOICE'" class="answer-options"><label v-for="o in q.options" :key="o.key" class="answer-option"><input v-model="drafts[q.id]" type="radio" :name="'q'+q.id" :value="o.key"> <strong>{{o.key}}</strong> {{o.text}}</label></div><textarea v-else v-model="drafts[q.id]" rows="7" placeholder="请输入你的答案"></textarea></div></article></div></template>
