<script setup>
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { teacherApi, statusNames, formatTime } from '../../api/teacher'
import TeacherModal from '../../components/TeacherModal.vue'
import ExamForm from '../../components/ExamForm.vue'
import QuestionForm from '../../components/QuestionForm.vue'
import QuestionBankPicker from '../../components/QuestionBankPicker.vue'
const route = useRoute(), exam = ref(null), questions = ref([]), error = ref(''), notice = ref(''), loading = ref(true), busy = ref(false)
const editExam = ref(false), editQuestion = ref(false), pickFromBank = ref(false), selected = ref(null)
async function load() {
  try { const [e,q] = await Promise.all([teacherApi.exam(route.params.examId), teacherApi.questions(route.params.examId)]); exam.value = e.data; questions.value = q.data }
  catch(e) { error.value = e.message } finally { loading.value = false }
}
function openQuestion(q) { selected.value = q; editQuestion.value = true }
async function saved() { editExam.value = false; editQuestion.value = false; notice.value = '保存成功'; await load() }
async function bankQuestionsAdded(count) { pickFromBank.value = false; notice.value = `已从题库加入 ${count} 道题`; await load() }
async function remove(q) {
  if (!window.confirm('确定删除题目“' + q.content.slice(0, 40) + '”及其评分点吗？')) return
  try { await teacherApi.deleteQuestion(q.id); notice.value = '题目已删除'; await load() } catch(e) { error.value = e.message }
}
async function publish() {
  if (!window.confirm('考试发布后关键规则和题目将受到限制，确定发布吗？')) return
  busy.value = true; error.value = ''
  try { await teacherApi.publish(exam.value.id); notice.value = '考试发布成功'; await load() } catch(e) { error.value = e.message } finally { busy.value = false }
}
onMounted(load)
</script>
<template>
  <p v-if="error" class="error-message" role="alert">{{ error }}</p><p v-if="notice" class="success-message" role="status">{{ notice }}</p>
  <p v-if="loading" class="empty-state">正在加载考试…</p>
  <template v-if="exam">
    <RouterLink :to="'/teacher/courses/' + exam.courseId">← {{ exam.courseName }}</RouterLink>
    <header class="page-heading"><div><p class="eyebrow">EXAM DESIGN</p><h1>{{ exam.title }}</h1><span class="badge" :class="exam.status">{{ statusNames[exam.status] }}</span></div>
      <div v-if="exam.status === 'DRAFT'" class="actions"><button class="secondary" @click="editExam = true">编辑考试</button><button class="primary" :disabled="busy" @click="publish">{{ busy ? '发布中…' : '发布考试' }}</button></div>
    </header>
    <section class="surface">
      <p class="preserve">{{ exam.description || '暂无考试说明' }}</p>
      <div class="meta-strip"><span>开始 {{ formatTime(exam.startTime) }}</span><span>结束 {{ formatTime(exam.endTime) }}</span><span>迟到允许 {{ exam.lateEntryMinutes }} 分钟</span><span>最早交卷 {{ exam.minSubmitMinutes }} 分钟</span></div>
      <p v-if="exam.status !== 'DRAFT'" class="notice">考试已发布，关键规则和题目已锁定。发布时间：{{ formatTime(exam.publishedAt) }}</p>
    </section>
    <div class="section-head"><h2>试卷题目 <span class="muted">共 {{ exam.questionCount }} 题 · 总分 {{ exam.totalScore }} 分</span></h2><div v-if="exam.status === 'DRAFT'" class="actions"><button class="secondary" @click="openQuestion(null)">+ 手动新增题目</button><button class="primary" @click="pickFromBank = true">从题库选题</button></div></div>
    <div v-if="!questions.length" class="empty-state">试卷还是空的，添加一道单选题或简答题开始设计。</div>
    <article v-for="q in questions" :key="q.id" class="surface question-card">
      <header class="section-head"><div class="actions"><span class="question-order">{{ q.sortOrder }}</span><span class="badge" :class="q.questionType">{{ q.questionType === 'SINGLE_CHOICE' ? '单选题' : '简答题' }}</span><strong>{{ q.score }} 分</strong></div><div v-if="exam.status === 'DRAFT'" class="actions"><button class="ghost" @click="openQuestion(q)">编辑题目</button><button class="danger" @click="remove(q)">删除题目</button></div></header>
      <h3 class="preserve">{{ q.content }}</h3>
      <div v-for="o in q.options" :key="o.key" class="option-row"><strong>{{ o.key }}.</strong> {{ o.text }}</div>
      <div class="answer-preview preserve"><strong>{{ q.questionType === 'SINGLE_CHOICE' ? '正确答案' : '参考答案' }}：</strong>{{ q.referenceAnswer }}</div>
      <div v-if="q.questionType === 'SHORT_ANSWER'"><h4>评分点 · 已分配 {{ (q.scoringPoints.reduce((n,p) => n + Math.round(p.pointScore * 100), 0) / 100).toFixed(2) }} / {{ q.score }} 分</h4><p v-if="!q.scoringPoints.length" class="muted">尚未配置评分点</p><ol><li v-for="p in q.scoringPoints" :key="p.id">{{ p.pointContent }} <strong>{{ p.pointScore }} 分</strong></li></ol></div>
    </article>
    <TeacherModal v-if="editExam" title="编辑考试" @close="editExam = false"><ExamForm :course-id="exam.courseId" :exam="exam" @saved="saved" /></TeacherModal>
    <TeacherModal v-if="editQuestion" :title="selected ? '编辑题目' : '添加题目'" @close="editQuestion = false"><QuestionForm :exam-id="exam.id" :question="selected" :next-order="questions.length ? Math.max(...questions.map(q => q.sortOrder)) + 1 : 1" @saved="saved" /></TeacherModal>
    <TeacherModal v-if="pickFromBank" title="从题库选题" @close="pickFromBank = false"><QuestionBankPicker :exam-id="exam.id" @saved="bankQuestionsAdded" /></TeacherModal>
  </template>
</template>
