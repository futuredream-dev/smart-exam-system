<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { teacherApi, formatTime } from '../api/teacher'
import { displayStageNames } from '../utils/examCenter'
import { eventTypeNames, filterMonitorStudents, monitorIsRunning, studentStatusNames } from '../utils/examMonitor'
import TeacherModal from './TeacherModal.vue'

const props = defineProps({ examId: { type: [Number, String], required: true } })
const snapshot = ref(null)
const loading = ref(true), refreshing = ref(false), error = ref(''), lastRefreshedAt = ref(null)
const keyword = ref(''), status = ref('ALL')
const selectedStudent = ref(null), eventPage = ref({ items: [], total: 0, page: 1, size: 10 })
const eventsLoading = ref(false), eventsError = ref('')
const visibleStudents = computed(() => filterMonitorStudents(snapshot.value?.students, keyword.value, status.value))
const eventPageCount = computed(() => Math.max(1, Math.ceil(eventPage.value.total / eventPage.value.size)))
const filters = [
  ['ALL', '全部'], ['NOT_ENTERED', '未进入'], ['IN_PROGRESS', '考试中'],
  ['SUBMITTED', '已交卷'], ['HAS_EVENTS', '有行为记录'],
]
let pollTimer = null
let disposed = false
let eventRequestId = 0

function reconcilePolling() {
  const shouldPoll = !disposed && snapshot.value && monitorIsRunning(snapshot.value.runtimePhase)
    && document.visibilityState === 'visible'
  if (!shouldPoll && pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  } else if (shouldPoll && !pollTimer) {
    pollTimer = setInterval(() => { void refresh('auto') }, 15000)
  }
}

async function refresh(reason = 'manual') {
  if (disposed || refreshing.value) return
  refreshing.value = true
  if (!snapshot.value) loading.value = true
  if (reason === 'manual') error.value = ''
  try {
    const result = await teacherApi.examMonitor(props.examId)
    if (disposed) return
    snapshot.value = result.data
    lastRefreshedAt.value = new Date()
    error.value = ''
  } catch (failure) {
    if (!disposed) error.value = reason === 'auto'
      ? '自动刷新失败，仍显示上次成功的数据。'
      : (failure.message || '考务数据加载失败，请重试。')
  } finally {
    if (!disposed) {
      loading.value = false
      refreshing.value = false
      reconcilePolling()
    }
  }
}

function onVisibilityChange() {
  if (document.visibilityState === 'hidden') {
    reconcilePolling()
  } else if (snapshot.value && monitorIsRunning(snapshot.value.runtimePhase)) {
    void refresh('auto')
    reconcilePolling()
  }
}

function eventTime(value) { return value ? value.replace('T', ' ').slice(0, 19) : '—' }

async function loadEvents(page) {
  if (!selectedStudent.value || eventsLoading.value) return
  const requestId = ++eventRequestId
  const studentId = selectedStudent.value.studentId
  eventsLoading.value = true
  eventsError.value = ''
  try {
    const result = await teacherApi.examMonitorEvents(props.examId, studentId, page, 10)
    if (requestId === eventRequestId && selectedStudent.value?.studentId === studentId)
      eventPage.value = result.data
  } catch (failure) {
    if (requestId === eventRequestId) eventsError.value = failure.message || '行为记录加载失败，请重试。'
  } finally {
    if (requestId === eventRequestId) eventsLoading.value = false
  }
}

function openEvents(student) {
  if (!student.eventCount) return
  selectedStudent.value = student
  eventPage.value = { items: [], total: 0, page: 1, size: 10 }
  void loadEvents(1)
}

function closeEvents() {
  eventRequestId++
  selectedStudent.value = null
  eventsLoading.value = false
}

onMounted(() => {
  document.addEventListener('visibilitychange', onVisibilityChange)
  void refresh('initial')
})
onBeforeUnmount(() => {
  disposed = true
  closeEvents()
  document.removeEventListener('visibilitychange', onVisibilityChange)
  if (pollTimer) clearInterval(pollTimer)
  pollTimer = null
})
</script>

<template>
  <section class="exam-monitor exam-center-panel" aria-label="考试考务">
    <header class="section-head">
      <div><h2>考试考务</h2><p class="muted">查看应考学生的进入、答题、交卷和行为记录。</p></div>
      <button class="secondary" :disabled="refreshing" @click="refresh('manual')">{{ refreshing ? '刷新中…' : '刷新' }}</button>
    </header>
    <p v-if="error" class="notice" role="status">{{ error }}</p>
    <p v-if="loading && !snapshot" class="surface empty-state">正在加载考务数据…</p>
    <p v-else-if="!snapshot" class="surface empty-state">暂无考务数据，请刷新重试。</p>
    <template v-else>
      <div class="exam-monitor-heading surface">
        <strong>{{ snapshot.examName }}</strong>
        <span>开始 {{ formatTime(snapshot.startTime) }}</span>
        <span>结束 {{ formatTime(snapshot.endTime) }}</span>
        <span>当前阶段：{{ displayStageNames[snapshot.runtimePhase] || (monitorIsRunning(snapshot.runtimePhase) ? '进行中' : snapshot.runtimePhase) }}</span>
      </div>
      <div class="exam-monitor-summary" aria-label="考务统计">
        <div v-for="item in [
          ['应考人数', snapshot.summary.candidateCount], ['已进入', snapshot.summary.enteredCount],
          ['考试中', snapshot.summary.inProgressCount], ['已交卷', snapshot.summary.submittedCount],
          ['未进入', snapshot.summary.notEnteredCount], ['有行为记录', snapshot.summary.eventStudentCount],
        ]" :key="item[0]"><span>{{ item[0] }}</span><strong>{{ item[1] }}</strong></div>
      </div>
      <div class="exam-monitor-controls surface">
        <label>姓名 / 学号<input v-model="keyword" type="search" placeholder="搜索学生姓名或学号" maxlength="100" /></label>
        <label>状态<select v-model="status"><option v-for="item in filters" :key="item[0]" :value="item[0]">{{ item[1] }}</option></select></label>
        <span class="muted">最近刷新：{{ lastRefreshedAt ? lastRefreshedAt.toLocaleTimeString('zh-CN', { hour12: false }) : '—' }}</span>
      </div>
      <div class="surface exam-monitor-list">
        <p v-if="snapshot.summary.candidateCount === 0" class="empty-state">暂无应考学生。</p>
        <p v-else-if="visibleStudents.length === 0" class="empty-state">没有符合搜索或筛选条件的学生。</p>
        <div v-else class="table-scroll"><table>
          <thead><tr><th>姓名</th><th>学号</th><th>状态</th><th>答题进度</th><th>首次进入</th><th>最后进入</th><th>交卷时间</th><th>行为记录</th><th class="table-action">操作</th></tr></thead>
          <tbody><tr v-for="student in visibleStudents" :key="student.studentId">
            <td>{{ student.studentName }}</td><td>{{ student.studentNo }}</td>
            <td><span class="badge">{{ studentStatusNames[student.displayStatus] || student.displayStatus || '—' }}</span></td>
            <td>{{ student.answeredCount ?? 0 }} / {{ student.totalQuestionCount ?? snapshot.totalQuestionCount }}</td>
            <td>{{ formatTime(student.firstEnterTime) }}</td><td>{{ formatTime(student.lastEnterTime) }}</td><td>{{ formatTime(student.submitTime) }}</td>
            <td>{{ student.eventCount ? `${student.eventCount} 条` : '0' }}</td>
            <td class="table-action"><button v-if="student.eventCount > 0" class="ghost" @click="openEvents(student)">查看记录</button><span v-else>—</span></td>
          </tr></tbody>
        </table></div>
      </div>
      <p class="muted exam-monitor-footnote">行为记录仅用于了解考试过程，不代表系统对学生作出判断。</p>
    </template>

    <TeacherModal v-if="selectedStudent" :title="`${selectedStudent.studentName}的行为记录`" @close="closeEvents">
      <div class="exam-monitor-events">
        <p class="muted">{{ selectedStudent.studentName }} · 学号 {{ selectedStudent.studentNo }}</p>
        <p class="muted">行为记录仅用于了解考试过程，不代表系统对学生作出判断。</p>
        <p v-if="eventsError" class="notice" role="status">{{ eventsError }}</p>
        <p v-if="eventsLoading && !eventPage.items.length" class="empty-state">正在加载行为记录…</p>
        <p v-else-if="!eventPage.items.length" class="empty-state">暂无行为记录。</p>
        <div v-else class="table-scroll"><table><thead><tr><th>时间</th><th>类型</th></tr></thead>
          <tbody><tr v-for="event in eventPage.items" :key="event.id"><td>{{ eventTime(event.occurredAt) }}</td><td>{{ eventTypeNames[event.eventType] || event.eventType }}</td></tr></tbody>
        </table></div>
        <div v-if="eventPage.total > eventPage.size" class="exam-monitor-pagination">
          <span>第 {{ eventPage.page }} / {{ eventPageCount }} 页 · 共 {{ eventPage.total }} 条</span>
          <button class="secondary" :disabled="eventsLoading || eventPage.page <= 1" @click="loadEvents(eventPage.page - 1)">上一页</button>
          <button class="secondary" :disabled="eventsLoading || eventPage.page >= eventPageCount" @click="loadEvents(eventPage.page + 1)">下一页</button>
        </div>
      </div>
    </TeacherModal>
  </section>
</template>
