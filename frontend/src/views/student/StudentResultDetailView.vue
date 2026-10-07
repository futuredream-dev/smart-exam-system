<script setup>
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { studentApi, formatTime } from '../../api/student'

const route = useRoute()
const result = ref(null), error = ref(''), loading = ref(true)
onMounted(async () => {
  try { result.value = (await studentApi.result(route.params.submissionId)).data }
  catch (e) { error.value = e.message || '加载成绩详情失败，请刷新重试' }
  finally { loading.value = false }
})
const typeName = type => type === 'SINGLE_CHOICE' ? '单选题' : '简答题'
</script>

<template>
  <div class="student-result-detail">
    <RouterLink class="student-back-link" to="/student/results">返回我的成绩</RouterLink>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <p v-if="loading" class="loading-state" role="status">正在加载成绩详情…</p>
    <template v-if="result">
      <header class="surface student-result-summary">
        <div class="student-result-heading"><div><h1>{{ result.examTitle }}</h1><p class="muted">{{ result.courseName }}</p><small class="muted">{{ formatTime(result.publishedAt) }} 公布</small></div><div class="student-detail-score"><span>最终成绩</span><strong>{{ result.totalScore ?? '—' }}<small> / {{ result.maxScore }} 分</small></strong></div></div>
        <section v-if="result.teacherComment" class="student-teacher-comment" aria-labelledby="student-total-comment-title"><h2 id="student-total-comment-title">教师总评</h2><p class="preserve">{{ result.teacherComment }}</p></section>
      </header>
      <section class="student-result-review" aria-labelledby="student-result-review-title">
        <header class="section-head"><h2 id="student-result-review-title">答题回顾</h2><span class="muted">{{ result.questions.length }} 题</span></header>
        <div class="surface student-result-sheet">
          <article v-for="(question, index) in result.questions" :key="question.questionId" class="student-result-question">
            <header class="student-question-head"><h3>第 {{ index + 1 }} 题</h3><span>{{ typeName(question.questionType) }}</span><span class="student-question-final-score">最终得分 <strong>{{ question.finalScore ?? '—' }}</strong> / {{ question.maxScore }} 分</span></header>
            <p class="student-question-content preserve">{{ question.content }}</p>
            <ul v-if="question.questionType === 'SINGLE_CHOICE'" class="student-result-options"><li v-for="option in question.options" :key="option.key"><strong>{{ option.key }}</strong><span>{{ option.text }}</span></li></ul>
            <dl class="student-answer-review">
              <div><dt>我的答案</dt><dd class="preserve">{{ question.studentAnswer || '未作答' }}</dd></div>
              <div v-if="question.questionType === 'SINGLE_CHOICE'"><dt>正确答案</dt><dd>{{ question.correctAnswer || '—' }}</dd></div>
            </dl>
          </article>
        </div>
      </section>
    </template>
  </div>
</template>
