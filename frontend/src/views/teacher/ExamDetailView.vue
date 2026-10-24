<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { teacherApi, formatTime } from '../../api/teacher'
import { actionNames, displayStageNames } from '../../utils/examCenter'
import { resolveExamTab, teacherReturnPath } from '../../utils/examNavigation'
import TeacherModal from '../../components/TeacherModal.vue'
import ExamPublishReadiness from '../../components/ExamPublishReadiness.vue'
import ExamForm from '../../components/ExamForm.vue'
import QuestionForm from '../../components/QuestionForm.vue'
import QuestionBankPicker from '../../components/QuestionBankPicker.vue'
import RandomPaperComposer from '../../components/RandomPaperComposer.vue'
import RulePaperComposer from '../../components/RulePaperComposer.vue'
import AiPaperComposer from '../../components/AiPaperComposer.vue'
import ExamCandidatesPanel from '../../components/ExamCandidatesPanel.vue'
import ExamGradingPanel from '../../components/ExamGradingPanel.vue'
import ExamMonitorPanel from '../../components/ExamMonitorPanel.vue'
import ResultsAnalysisPanel from '../../components/ResultsAnalysisPanel.vue'
import CreateMakeupForm from '../../components/CreateMakeupForm.vue'

const route = useRoute(), router = useRouter()
const exam = ref(null), workspace = ref(null), questions = ref([])
const error = ref(''), notice = ref(''), loading = ref(true)
const editExam = ref(false), editQuestion = ref(false), pickFromBank = ref(false), randomCompose = ref(false), ruleCompose = ref(false), aiCompose = ref(false)
const aiRequirement = ref(''), ruleSeed = ref(null)
const aiBusy = ref(false)
const composerOpen = ref(false)
const publishOpen = ref(false), selected = ref(null)
const makeupOpen = ref(false), analysisRefreshKey = ref(0)
let loadId = 0
const draft = computed(() => exam.value?.status === 'DRAFT' && !exam.value?.archivedAt)
const canCreateMakeup = computed(() => exam.value && !exam.value.archivedAt && !draft.value && new Date(exam.value.endTime).getTime() <= Date.now())
const tabs = computed(() => [['overview', '概览'], ['paper', '试卷'], ['candidates', '考生与考务'], ['review', '阅卷'], ['grading', '成绩']])
const stage = computed(() => exam.value?.displayStage)
const resolvedTab = computed(() => resolveExamTab(route.query.tab, stage.value))
const active = computed(() => resolvedTab.value.key)
const affairsSection = computed(() => resolvedTab.value.section)
const returnTo = computed(() => teacherReturnPath(route.query.returnTo))
const questionTypes = computed(() => Object.entries(exam.value?.questionTypeCounts || {}).map(([type, count]) =>
  `${type === 'SINGLE_CHOICE' ? '单选题' : type === 'SHORT_ANSWER' ? '简答题' : type} ${count} 题`))

async function load() {
  const id = ++loadId
  loading.value = true; error.value = ''
  exam.value = null; workspace.value = null; questions.value = []
  try {
    const [detail, workspaceResult, paper] = await Promise.all([
      teacherApi.exam(route.params.examId), teacherApi.examWorkspace(route.params.examId), teacherApi.questions(route.params.examId),
    ])
    if (id !== loadId) return
    exam.value = detail.data; workspace.value = workspaceResult.data; questions.value = paper.data
  } catch (e) { if (id === loadId) error.value = e.message }
  finally { if (id === loadId) loading.value = false }
}
function tab(value) { router.push({ path: route.path, query: { ...route.query, tab: value } }) }
function primary() {
  if (exam.value.nextAction === 'PUBLISH') publishOpen.value = true
  else if (exam.value.nextAction === 'CONFIGURE_PAPER') tab('paper')
  else if (['START_GRADING', 'CONTINUE_GRADING'].includes(exam.value.nextAction)) tab('review')
  else if (['RELEASE_RESULTS', 'VIEW_RESULTS', 'CONTINUE_RELEASE'].includes(exam.value.nextAction)) tab('grading')
  else if (stage.value === 'RUNNING') tab('monitor')
  else if (stage.value === 'NO_PARTICIPANTS') tab('candidates')
  else tab('overview')
}
function openQuestion(question) { selected.value = question; editQuestion.value = true }
async function saved() { editExam.value = false; editQuestion.value = false; notice.value = '保存成功'; await load() }
async function bankQuestionsAdded(count) { pickFromBank.value = false; notice.value = `已从题库加入 ${count} 道题`; await load() }
async function randomPaperAdded(count) { randomCompose.value = false; notice.value = `已随机加入 ${count} 道题`; await load() }
async function rulePaperAdded(count) { ruleCompose.value = false; notice.value = `已按规则加入 ${count} 道题`; await load() }
async function aiPaperAdded(count) { aiCompose.value = false; notice.value = `已通过 AI 辅助组卷加入 ${count} 道题`; await load() }
function openRuleComposer() { ruleSeed.value = null; ruleCompose.value = true }
function switchAiToRules(draft) { aiRequirement.value = draft.requirement; ruleSeed.value = draft.rules; aiCompose.value = false; ruleCompose.value = true }
async function remove(question) {
  if (!confirm(`确定删除题目“${question.content.slice(0, 40)}”及其评分点吗？`)) return
  try { await teacherApi.deleteQuestion(question.id); notice.value = '题目已删除'; await load() }
  catch (e) { error.value = e.message }
}
async function published() { publishOpen.value = false; notice.value = '考试发布成功'; await load() }
function makeupCreated(created) { makeupOpen.value = false; router.push(`/teacher/exams/${created.id}`) }
async function resultsReleased() { analysisRefreshKey.value++; await load() }
function consumeNotice() {
  const key = `examWorkspaceNotice:${route.params.examId}`
  const tip = sessionStorage.getItem(key)
  if (tip) { sessionStorage.removeItem(key); notice.value = tip }
}
watch(() => route.params.examId, () => { consumeNotice(); load() })
watch(() => route.query.tab, load)
onMounted(() => { consumeNotice(); load() })
</script>

<template>
  <div class="exam-center-detail">
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <p v-if="notice" class="success-message" role="status">{{ notice }}</p>
    <p v-if="loading" class="loading-state" role="status">正在加载考试…</p>
    <template v-if="!loading && exam && workspace">
      <div class="exam-detail-breadcrumb"><RouterLink :to="returnTo">← 返回考试列表</RouterLink><span>/</span><span>考试详情</span></div>
      <header class="exam-center-detail-head">
        <div class="exam-detail-identity"><div class="exam-detail-title-line"><h1>{{ exam.title }}</h1><span :class="['badge', stage]">{{ displayStageNames[stage] || stage }}</span><span v-if="exam.examKind === 'MAKEUP'" class="badge">补考</span><span v-if="exam.archivedAt" class="badge">已归档</span></div>
          <p class="exam-detail-meta"><span>{{ exam.courseName }}</span><span>{{ formatTime(exam.startTime) }} 至 {{ formatTime(exam.endTime) }}</span></p></div>
        <div class="actions"><button class="primary" @click="primary">{{ actionNames[exam.nextAction] || '查看考试' }}</button></div>
      </header>
      <p v-if="stage === 'NO_PARTICIPANTS'" class="notice">考试已结束，本场无人交卷，无需阅卷或发布成绩。可核对考生情况，或创建补考。</p>
      <nav class="workspace-tabs" aria-label="考试详情"><button v-for="item in tabs" :key="item[0]" :aria-current="active === item[0] ? 'page' : undefined" :class="{ active: active === item[0] }" @click="tab(item[0])">{{ item[1] }}</button></nav>

      <section v-if="active === 'overview'" class="surface exam-center-overview">
        <div><h2>基本信息</h2><dl><dt>考试名称</dt><dd>{{ exam.title }}</dd><dt>所属课程</dt><dd>{{ exam.courseName }}</dd><dt>考试说明</dt><dd class="preserve">{{ exam.description || '暂无考试说明' }}</dd><dt>开始时间</dt><dd>{{ formatTime(exam.startTime) }}</dd><dt>结束时间</dt><dd>{{ formatTime(exam.endTime) }}</dd></dl></div>
        <div><h2>考试规则与试卷</h2><dl><dt>迟到限制</dt><dd>{{ exam.lateEntryMinutes }} 分钟</dd><dt>最早交卷</dt><dd>{{ exam.minSubmitMinutes }} 分钟</dd><dt>试卷</dt><dd>{{ exam.questionCount }} 题 / {{ exam.totalScore }} 分</dd><dt>题型</dt><dd>{{ questionTypes.join('、') || '尚未配置' }}</dd><dt>应参考</dt><dd>{{ exam.eligibleStudentCount }} 人</dd><dt>已提交</dt><dd>{{ exam.submittedCount }} 人</dd><dt>未提交</dt><dd>{{ Math.max(0, exam.eligibleStudentCount - exam.submittedCount) }} 人</dd></dl>
          <dl><dt>已评分</dt><dd>{{ exam.gradedCount }} 份</dd><dt>待阅卷</dt><dd>{{ exam.pendingGradingCount }} 份</dd><dt>成绩状态</dt><dd>{{ stage === 'NO_PARTICIPANTS' ? '无需发布' : stage === 'COMPLETED' ? '成绩已发布' : stage === 'RESULT_PARTIAL' ? '成绩部分发布' : '成绩未发布' }}</dd></dl>
          <div class="actions"><button v-if="draft" class="secondary" @click="editExam = true">编辑考试</button><button v-if="canCreateMakeup" class="secondary" @click="makeupOpen = true">创建补考</button></div></div>
      </section>

      <section v-if="active === 'paper'" class="exam-center-panel">
        <div class="section-head"><div><h2>试卷</h2><p class="muted">{{ exam.questionCount }} 题 / {{ exam.totalScore }} 分<span v-if="questionTypes.length"> · {{ questionTypes.join('、') }}</span></p></div><div v-if="draft" class="actions"><button class="secondary" :aria-expanded="composerOpen" aria-controls="exam-composer-options" @click="composerOpen = !composerOpen">从题库组卷</button><button class="secondary" @click="openQuestion(null)">手动出题</button></div></div>
        <section v-if="draft && composerOpen" id="exam-composer-options" class="surface exam-composer-options" aria-label="题库组卷方式">
          <div class="section-head"><h3>选择组卷方式</h3><button class="ghost" @click="composerOpen = false">收起方式</button></div>
          <div class="exam-composer-grid"><button class="secondary" @click="pickFromBank = true"><strong>手动选题</strong><span>浏览题库，逐题选择</span></button><button class="secondary" @click="randomCompose = true"><strong>随机组卷</strong><span>按题型和数量随机抽取</span></button><button class="secondary" @click="openRuleComposer"><strong>规则智能组卷</strong><span>按知识点、难度配置规则</span></button><button class="secondary" @click="aiCompose = true"><strong>AI 辅助组卷</strong><span>描述需求，确认选题方案</span></button></div>
        </section>
        <p v-if="!questions.length" class="surface empty-state">当前考试尚未配置试卷。</p>
        <p v-if="!draft" class="notice">考试已发布，试卷内容已锁定。</p>
        <article v-for="question in questions" :key="question.id" class="surface question-card"><header class="section-head"><div class="actions"><span class="question-order">{{ question.sortOrder }}</span><span :class="['badge', question.questionType]">{{ question.questionType === 'SINGLE_CHOICE' ? '单选题' : '简答题' }}</span><strong>{{ question.score }} 分</strong></div><div v-if="draft" class="actions"><button class="ghost" @click="openQuestion(question)">编辑题目</button><button class="danger" @click="remove(question)">删除题目</button></div></header><h3 class="preserve">{{ question.content }}</h3><div v-for="option in question.options" :key="option.key" class="option-row"><strong>{{ option.key }}.</strong> {{ option.text }}</div><div class="answer-preview preserve"><strong>{{ question.questionType === 'SINGLE_CHOICE' ? '正确答案' : '参考答案' }}：</strong>{{ question.referenceAnswer }}</div><div v-if="question.questionType === 'SHORT_ANSWER'"><h4>评分点</h4><ol><li v-for="point in question.scoringPoints" :key="point.id">{{ point.pointContent }} <strong>{{ point.pointScore }} 分</strong></li></ol></div></article>
      </section>

      <section v-if="active === 'candidates'" class="exam-center-panel">
        <div class="section-head"><div><h2>考生与考务</h2><p class="muted">管理应考名单，查看考生参与和交卷情况。</p></div></div>
        <nav class="exam-affairs-tabs" aria-label="考生与考务栏目"><button class="ghost" :aria-current="affairsSection === 'candidates' ? 'page' : undefined" @click="tab('candidates')">考生名单</button><button class="ghost" :aria-current="affairsSection === 'monitor' ? 'page' : undefined" @click="tab('monitor')">考务监控</button></nav>
        <ExamCandidatesPanel v-if="affairsSection === 'candidates'" :exam-id="exam.id" :draft="draft" />
        <ExamMonitorPanel v-else-if="!draft" :exam-id="exam.id" /><p v-else class="surface empty-state">考试发布后可查看考务监控。</p>
      </section>

      <section v-if="active === 'review'" class="exam-center-panel"><div class="section-head"><div><h2>阅卷</h2><p class="muted">已提交 {{ exam.submittedCount }} 份 · 已评分 {{ exam.gradedCount }} 份 · 待阅 {{ exam.pendingGradingCount }} 份</p></div><RouterLink class="ghost" to="/teacher/grading-tasks">跨考试阅卷任务</RouterLink></div><ExamGradingPanel mode="review" :exam-id="exam.id" :workspace="workspace" :return-to="route.query.returnTo" /></section>

      <section v-if="active === 'grading'" class="exam-center-panel"><div class="section-head"><div><h2>成绩</h2><p v-if="stage === 'COMPLETED'" class="muted">本场考试成绩已发布。</p><p v-else-if="stage === 'RESULT_PARTIAL'" class="muted">存在历史部分发布记录，请核对成绩状态。</p><p v-else-if="stage === 'GRADED'" class="muted">阅卷已完成，可以统一发布本场考试成绩。</p><p v-else-if="exam.submittedCount === 0" class="muted">暂无已提交答卷，当前无可发布成绩。</p><p v-else class="muted">仍有答卷尚未完成阅卷，完成阅卷后可统一发布成绩。</p></div><button v-if="exam.pendingGradingCount" class="secondary" @click="tab('review')">继续阅卷</button></div><ExamGradingPanel mode="results" :exam-id="exam.id" :workspace="workspace" @released="resultsReleased" /><ResultsAnalysisPanel :exam-id="exam.id" :refresh-key="analysisRefreshKey" :display-stage="stage" /><RouterLink class="ghost" :to="{ path: '/teacher/ai-evaluation', query: { examId: String(exam.id), returnTo: route.fullPath } }">查看本场 AI 评分分析 →</RouterLink></section>

      <TeacherModal v-if="editExam" title="编辑考试" @close="editExam = false"><ExamForm :course-id="exam.courseId" :exam="exam" @saved="saved" /></TeacherModal>
      <TeacherModal v-if="editQuestion" :title="selected ? '编辑题目' : '添加题目'" @close="editQuestion = false"><QuestionForm :exam-id="exam.id" :question="selected" :next-order="questions.length ? Math.max(...questions.map(q => q.sortOrder)) + 1 : 1" @saved="saved" /></TeacherModal>
      <TeacherModal v-if="pickFromBank" title="从题库选题" @close="pickFromBank = false"><QuestionBankPicker :exam-id="exam.id" :course-id="exam.courseId" @saved="bankQuestionsAdded" /></TeacherModal>
      <TeacherModal v-if="randomCompose" title="随机组卷" @close="randomCompose = false"><RandomPaperComposer :exam-id="exam.id" :course-id="exam.courseId" @saved="randomPaperAdded" @close="randomCompose = false" /></TeacherModal>
      <TeacherModal v-if="ruleCompose && draft" title="规则智能组卷" @close="ruleCompose = false"><RulePaperComposer :exam-id="exam.id" :course-id="exam.courseId" :initial-rules="ruleSeed" @saved="rulePaperAdded" @close="ruleCompose = false" /></TeacherModal>
      <TeacherModal v-if="aiCompose && draft" title="AI 辅助组卷" @close="!aiBusy && (aiCompose = false)"><AiPaperComposer :exam-id="exam.id" :course-id="exam.courseId" :initial-requirement="aiRequirement" @requirement-change="aiRequirement = $event" @busy-change="aiBusy = $event" @saved="aiPaperAdded" @switch-to-rule="switchAiToRules" /></TeacherModal>
      <ExamPublishReadiness v-if="publishOpen" :exam-id="exam.id" :title="exam.title" @close="publishOpen = false" @published="published" />
      <TeacherModal v-if="makeupOpen" title="创建补考" @close="makeupOpen = false"><CreateMakeupForm :exam="exam" @created="makeupCreated" /></TeacherModal>
    </template>
  </div>
</template>
