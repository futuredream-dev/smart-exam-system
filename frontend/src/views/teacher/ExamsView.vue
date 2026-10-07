<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { teacherApi, formatTime } from '../../api/teacher'
import { actionNames, actionRoute, displayStageNames, stageTabs } from '../../utils/examCenter'
import { examDisplayStage, statusTone } from '../../utils/statusDisplay'
import ExamForm from '../../components/ExamForm.vue'
import ExamPublishReadiness from '../../components/ExamPublishReadiness.vue'
import TeacherModal from '../../components/TeacherModal.vue'

const route = useRoute(), router = useRouter()
const courses = ref([]), rows = ref([]), total = ref(0), loading = ref(false), error = ref(''), notice = ref('')
const creating = ref(false), createCourseId = ref(''), publishExam = ref(null)
const copying = ref(null), copyTitle = ref(''), targetCourseId = ref(''), actionBusy = ref(false)
const archiveTarget = ref(null)
const draft = reactive({ keyword: '', courseId: '' })
let requestId = 0
const pageSize = 10
const tabKey = computed(() => stageTabs.some(tab => tab.key === route.query.tab) ? route.query.tab : '')
const tab = computed(() => stageTabs.find(item => item.key === tabKey.value))
const currentPage = computed(() => Math.max(1, Number.parseInt(route.query.page, 10) || 1))
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))
const appliedKeyword = computed(() => String(route.query.keyword || '').trim())
const appliedCourseId = computed(() => String(route.query.courseId || ''))

function queryFor(overrides = {}) {
  const values = { tab: tabKey.value, keyword: appliedKeyword.value, courseId: appliedCourseId.value, page: currentPage.value, ...overrides }
  const query = {}
  if (values.tab) query.tab = values.tab
  if (values.keyword) query.keyword = values.keyword
  if (values.courseId) query.courseId = values.courseId
  if (values.page > 1) query.page = String(values.page)
  return query
}
function search() { router.push({ path: route.path, query: queryFor({ keyword: draft.keyword.trim(), courseId: draft.courseId, page: 1 }) }) }
function reset() { draft.keyword = ''; draft.courseId = ''; router.push({ path: route.path, query: queryFor({ keyword: '', courseId: '', page: 1 }) }) }
function selectTab(key) { router.push({ path: route.path, query: queryFor({ tab: key, page: 1 }) }) }
function changePage(page) { if (page >= 1 && page <= pageCount.value) router.push({ path: route.path, query: queryFor({ page }) }) }
function primary(row) {
  if (row.nextAction === 'PUBLISH') publishExam.value = row
  else router.push(actionRoute(row.examId, row.nextAction))
}
function scoreLabel(row) {
  if (row.displayStage === 'COMPLETED') return '成绩已发布'
  if (row.displayStage === 'RESULT_PARTIAL') return '成绩部分发布'
  return '成绩未发布'
}
async function load() {
  const id = ++requestId
  loading.value = true; error.value = ''
  try {
    const result = (await teacherApi.examCenter({ keyword: appliedKeyword.value || undefined,
      courseId: appliedCourseId.value || undefined, stage: tab.value.stage || tab.value.stages?.[0] || undefined,
      archived: tabKey.value === 'archived', page: currentPage.value, size: pageSize })).data
    if (id === requestId) { rows.value = result.items; total.value = result.total }
  } catch (e) { if (id === requestId) { error.value = e.message; rows.value = []; total.value = 0 } }
  finally { if (id === requestId) loading.value = false }
}
function published() { publishExam.value = null; notice.value = '考试发布成功'; load() }
function openCopy(row) { copying.value = row; copyTitle.value = `${row.title.slice(0, 156)}（副本）`; targetCourseId.value = String(row.courseId) }
async function confirmCopy() {
  if (!copying.value || actionBusy.value) return
  actionBusy.value = true; error.value = ''
  try { const created = (await teacherApi.copyExam(copying.value.examId, { title: copyTitle.value, targetCourseId: Number(targetCourseId.value) })).data; copying.value = null; router.push(`/teacher/exams/${created.id}`) }
  catch (e) { error.value = e.message } finally { actionBusy.value = false }
}
function askArchive(row) { if (!actionBusy.value) { error.value = ''; archiveTarget.value = row } }
async function changeArchive() {
  if (!archiveTarget.value || actionBusy.value) return
  const row = archiveTarget.value
  actionBusy.value = true; error.value = ''
  try { if (row.archivedAt) await teacherApi.restoreExam(row.examId); else await teacherApi.archiveExam(row.examId); archiveTarget.value = null; notice.value = row.archivedAt ? '考试已恢复' : '考试已归档'; await load() }
  catch (e) { error.value = e.message } finally { actionBusy.value = false }
}
onMounted(async () => {
  draft.keyword = appliedKeyword.value; draft.courseId = appliedCourseId.value
  try { courses.value = (await teacherApi.courses()).data } catch (e) { error.value = e.message }
  load()
})
watch(() => route.fullPath, () => { draft.keyword = appliedKeyword.value; draft.courseId = appliedCourseId.value; load() })
</script>

<template>
  <div class="exam-center-page">
    <header class="page-heading"><div><h1>考试管理</h1><p class="muted">管理课程考试、试卷配置、考试进度和成绩发布</p></div><button class="primary" @click="creating = true">创建考试</button></header>
    <p v-if="notice" class="success-message" role="status">{{ notice }}</p>
    <p v-if="error && !archiveTarget && !copying" class="error-message" role="alert">{{ error }}</p>
    <div class="exam-stage-label">按考试阶段筛选</div>
    <nav class="exam-center-tabs" aria-label="考试阶段筛选"><button v-for="item in stageTabs" :key="item.key" :aria-pressed="tabKey === item.key" :class="{ active: tabKey === item.key }" @click="selectTab(item.key)">{{ item.label }}</button></nav>
    <form class="surface exam-center-filters" @submit.prevent="search">
      <label>考试名称<input v-model="draft.keyword" placeholder="输入考试名称" maxlength="200" /></label>
      <label>所属课程<select v-model="draft.courseId"><option value="">全部课程</option><option v-for="course in courses" :key="course.id" :value="String(course.id)">{{ course.courseName }}</option></select></label>
      <div class="actions"><button class="secondary" type="submit">查询</button><button class="ghost" type="button" @click="reset">重置</button></div>
    </form>
    <section class="surface exam-center-results">
      <div class="section-head"><h2>考试列表</h2><span class="muted">共 {{ total }} 场</span></div>
      <p v-if="loading" class="loading-state" role="status">正在加载考试…</p>
      <p v-else-if="error && !rows.length" class="empty-state">考试列表加载失败，请重新查询。</p>
      <div v-else-if="!rows.length" class="empty-state"><template v-if="!tabKey && !appliedKeyword && !appliedCourseId">暂无考试。<button class="secondary" @click="creating = true">创建考试</button></template><template v-else>没有符合当前条件的考试，请调整筛选条件。</template></div>
      <div v-else class="table-scroll"><table><thead><tr><th>考试 / 课程</th><th>考试时间</th><th>试卷</th><th>参考 / 阅卷</th><th>成绩</th><th>当前阶段</th><th class="table-action">操作</th></tr></thead>
        <tbody><tr v-for="row in rows" :key="row.examId">
          <td class="exam-center-title-cell"><RouterLink :to="`/teacher/exams/${row.examId}`"><strong>{{ row.title }}</strong></RouterLink><small>{{ row.courseName }}<span v-if="row.examKind === 'MAKEUP'"> · 补考</span><span v-if="row.archivedAt"> · 已归档</span></small></td>
          <td>{{ formatTime(row.startTime) }}<small>至 {{ formatTime(row.endTime) }}</small></td>
          <td>{{ row.questionCount }} 题<small>{{ row.totalScore }} 分</small></td>
          <td>{{ row.submittedCount }} / {{ row.eligibleStudentCount }} 已交<small>{{ ['DRAFT', 'SCHEDULED', 'RUNNING'].includes(row.displayStage) ? '阅卷未开始' : `已阅 ${row.gradedCount} / ${row.submittedCount}` }}</small></td>
          <td class="exam-center-score-cell">{{ scoreLabel(row) }}</td>
          <td><span :class="['badge', row.displayStage]" :data-tone="statusTone(examDisplayStage, row.displayStage)">{{ displayStageNames[row.displayStage] || row.displayStage }}</span></td>
          <td class="table-action"><div class="exam-row-actions"><button class="ghost" @click="primary(row)">{{ actionNames[row.nextAction] || '查看考试' }}</button><details><summary>更多</summary><div><button type="button" class="ghost" @click="openCopy(row)">复制考试</button><button v-if="row.archivedAt || row.displayStage === 'DRAFT' || row.displayStage === 'COMPLETED'" type="button" class="ghost" :disabled="actionBusy" @click="askArchive(row)">{{ row.archivedAt ? '恢复考试' : '归档考试' }}</button></div></details></div></td>
        </tr></tbody></table></div>
      <div v-if="total > pageSize" class="exam-center-pagination"><span>第 {{ currentPage }} / {{ pageCount }} 页</span><button class="secondary" :disabled="currentPage <= 1" @click="changePage(currentPage - 1)">上一页</button><button class="secondary" :disabled="currentPage >= pageCount" @click="changePage(currentPage + 1)">下一页</button></div>
    </section>
    <TeacherModal v-if="creating" title="创建考试" @close="creating = false"><div class="exam-create-course"><label>所属课程<select v-model="createCourseId"><option value="">请选择课程</option><option v-for="course in courses" :key="course.id" :value="String(course.id)">{{ course.courseName }}</option></select></label><RouterLink v-if="!courses.length" to="/teacher/courses">先创建课程</RouterLink></div><ExamForm v-if="createCourseId" :course-id="Number(createCourseId)" @saved="exam => { creating = false; router.push(`/teacher/exams/${exam.id}`) }" /></TeacherModal>
    <TeacherModal v-if="copying" title="复制考试" :busy="actionBusy" @close="copying = null"><form class="form-grid" @submit.prevent="confirmCopy"><label>新考试名称<input v-model.trim="copyTitle" required maxlength="160"></label><label>目标课程<select v-model="targetCourseId" required><option v-for="course in courses" :key="course.id" :value="String(course.id)">{{ course.courseName }}</option></select></label><p class="muted">复制试卷与规则，创建为普通草稿；考生、答卷和成绩不会复制。</p><p v-if="error" class="error-message" role="alert">{{ error }}</p><button class="primary" :disabled="actionBusy">{{ actionBusy ? '复制中…' : '确认复制' }}</button></form></TeacherModal>
    <TeacherModal v-if="archiveTarget" :title="archiveTarget.archivedAt ? '确认恢复考试' : '确认归档考试'" size="confirm" :busy="actionBusy" @close="archiveTarget = null">
      <div class="confirmation-copy"><p><strong>{{ archiveTarget.title }}</strong></p><p v-if="archiveTarget.archivedAt">恢复后，考试将回到未归档列表。原有答卷和成绩保持不变。</p><p v-else>归档后，考试移入“已归档”列表。原有答卷和成绩保留，可通过恢复考试重新查看。</p><p v-if="error" class="error-message" role="alert">{{ error }}</p></div>
      <template #footer><button class="secondary" :disabled="actionBusy" @click="archiveTarget = null">取消</button><button class="primary" :disabled="actionBusy" @click="changeArchive">{{ actionBusy ? '处理中…' : archiveTarget.archivedAt ? '确认恢复' : '确认归档' }}</button></template>
    </TeacherModal>
    <ExamPublishReadiness v-if="publishExam" :exam-id="publishExam.examId" :title="publishExam.title" @close="publishExam = null" @published="published" />
  </div>
</template>
