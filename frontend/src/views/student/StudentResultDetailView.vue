<script setup>
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { studentApi, formatTime } from '../../api/student'

const route = useRoute()
const result = ref(null)
const error = ref('')
const loading = ref(true)
onMounted(async () => {
  try { result.value = (await studentApi.result(route.params.submissionId)).data }
  catch (e) { error.value = e.message }
  finally { loading.value = false }
})
const typeName = type => type === 'SINGLE_CHOICE' ? '单选题' : '简答题'
</script>

<template>
  <p><RouterLink to="/student/results">← 返回我的成绩</RouterLink></p>
  <p v-if="error" class="error-message">{{ error }}</p>
  <p v-if="loading" class="empty-state">正在加载成绩详情…</p>
  <template v-if="result">
    <header class="surface result-summary">
      <div><h1>{{ result.examTitle }}</h1><p class="muted">{{ result.courseName }} · {{ formatTime(result.publishedAt) }} 公布</p></div>
      <div class="result-final-score"><span>最终成绩</span><strong>{{ result.totalScore }} / {{ result.maxScore }}</strong></div>
      <div class="teacher-summary"><span class="muted">教师总评</span><p class="preserve">{{ result.teacherComment || '暂无教师总评' }}</p></div>
    </header>
    <section class="result-review"><div class="section-head"><h2>答题回顾</h2></div>
      <article v-for="(question,index) in result.questions" :key="question.questionId" class="surface question-card result-question">
        <div class="question-result-heading"><h3>第 {{ index + 1 }} 题</h3><span :class="['badge',question.questionType]">{{ typeName(question.questionType) }} · {{ question.finalScore ?? '—' }} / {{ question.maxScore }} 分</span></div>
        <p class="preserve">{{ question.content }}</p>
        <ul v-if="question.questionType==='SINGLE_CHOICE'" class="result-options"><li v-for="option in question.options" :key="option.key"><strong>{{ option.key }}</strong> {{ option.text }}</li></ul>
        <div class="answer-review-grid"><p><span class="muted">你的答案</span><strong>{{ question.studentAnswer || '未作答' }}</strong></p><p v-if="question.questionType==='SINGLE_CHOICE'"><span class="muted">正确答案</span><strong>{{ question.correctAnswer }}</strong></p></div>
      </article>
    </section>
  </template>
</template>
