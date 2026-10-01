<script setup>
import { onMounted, ref, watch } from 'vue'
import { teacherApi } from '../api/teacher'

const props = defineProps({ examId: { type: Number, required: true }, refreshKey: { type: Number, default: 0 } })
const data = ref(null), error = ref(''), loading = ref(false)
async function load() {
  loading.value = true; error.value = ''
  try { data.value = (await teacherApi.resultsAnalysis(props.examId)).data }
  catch (e) { error.value = e.message; data.value = null }
  finally { loading.value = false }
}
onMounted(load)
watch(() => [props.examId, props.refreshKey], load)
const statusNames = { ABSENT: '缺考', NOT_ENTERED: '未入场', IN_PROGRESS: '答题中', PENDING_REVIEW: '待阅', AUTO_GRADED: '待确认', REVIEWED: '已阅' }
const score = value => value == null ? '—' : value
const percent = value => value == null ? '—' : `${value}%`
</script>

<template>
  <div class="results-analysis">
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <p v-if="loading" class="empty-state">正在加载成绩分析…</p>
    <template v-else-if="data">
      <p v-if="!data.analysisReady" class="notice">最终统计暂不可用：考试未结束或仍有 {{ data.pendingGradingCount }} 份答卷未完成阅卷。学生状态仍可查看。</p>
      <section class="surface results-analysis-section">
        <div class="section-head"><h3>汇总统计</h3><span class="muted">试卷满分 {{ data.fullScore }} 分</span></div>
        <div class="results-stat-grid">
          <div><span>应考人数</span><strong>{{ data.summary.candidateCount }}</strong></div>
          <div><span>参考人数</span><strong>{{ data.summary.participantCount }}</strong></div>
          <div><span>最高分</span><strong>{{ score(data.summary.highestScore) }}</strong></div>
          <div><span>最低分</span><strong>{{ score(data.summary.lowestScore) }}</strong></div>
          <div><span>平均分</span><strong>{{ score(data.summary.averageScore) }}</strong></div>
          <div><span>及格人数</span><strong>{{ score(data.summary.passingCount) }}</strong></div>
          <div><span>及格率</span><strong>{{ percent(data.summary.passingRate) }}</strong></div>
        </div>
      </section>
      <section class="surface results-analysis-section">
        <h3>成绩分布</h3><p class="muted">按百分制得分率分组，及格线为 60%。</p>
        <p v-if="!data.analysisReady" class="empty-state">待完成阅卷后显示最终分布。</p>
        <div v-for="bucket in data.distribution" :key="bucket.label" class="results-bucket">
          <span>{{ bucket.label }}</span><div class="results-bucket-track"><div :style="{ width: data.summary.participantCount ? `${bucket.count / data.summary.participantCount * 100}%` : '0%' }" /></div><strong>{{ bucket.count }} 人</strong>
        </div>
      </section>
      <section class="surface results-analysis-section">
        <h3>学生成绩</h3>
        <div class="table-scroll"><table><thead><tr><th>姓名</th><th>账号</th><th>答卷状态</th><th>最终成绩</th></tr></thead>
          <tbody><tr v-for="student in data.students" :key="student.studentId"><td>{{ student.studentName }}</td><td>{{ student.username }}</td><td>{{ statusNames[student.candidateStatus] || student.candidateStatus }}</td><td>{{ score(student.totalScore) }}<template v-if="student.totalScore != null"> / {{ data.fullScore }}</template></td></tr></tbody></table></div>
        <p v-if="!data.students.length" class="empty-state">暂无应考学生。</p>
      </section>
      <section class="surface results-analysis-section">
        <h3>试题分析</h3>
        <p v-if="!data.analysisReady" class="empty-state">待完成阅卷后显示试题分析。</p>
        <div v-else class="table-scroll"><table><thead><tr><th>题号</th><th>题型</th><th>满分</th><th>作答人数</th><th>平均分</th><th>得分率</th><th>正确率</th></tr></thead>
          <tbody><tr v-for="question in data.questions" :key="question.questionId"><td>{{ question.sortOrder }}</td><td>{{ question.questionType === 'SINGLE_CHOICE' ? '单选题' : '简答题' }}</td><td>{{ question.fullScore }}</td><td>{{ question.answeredCount }}</td><td>{{ score(question.averageScore) }}</td><td>{{ percent(question.scoreRate) }}</td><td>{{ percent(question.correctRate) }}</td></tr></tbody></table></div>
      </section>
    </template>
  </div>
</template>
