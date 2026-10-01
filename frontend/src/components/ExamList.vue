<script setup>
import { statusNames, formatTime } from '../api/teacher'
defineProps({ exams: { type: Array, default: () => [] } })
</script>
<template>
  <div v-if="!exams.length" class="empty-state">暂无考试，请先进入课程创建第一场考试。</div>
  <div v-else class="table-scroll">
    <table><thead><tr><th>考试 / 课程</th><th>考试时间</th><th>规则（分钟）</th><th>状态</th><th>操作</th></tr></thead>
      <tbody><tr v-for="e in exams" :key="e.id">
        <td><strong>{{ e.title }}</strong> <span v-if="e.examKind === 'MAKEUP'" class="badge">补考</span><small>{{ e.courseName }}</small></td>
        <td>{{ formatTime(e.startTime) }}<small>至 {{ formatTime(e.endTime) }}</small></td>
        <td>迟到 {{ e.lateEntryMinutes }}<small>最早交卷 {{ e.minSubmitMinutes }}</small></td>
        <td><span class="badge" :class="e.status">{{ statusNames[e.status] }}</span></td>
        <td class="table-action"><RouterLink class="ghost" :to="'/teacher/exams/' + e.id">管理考试 →</RouterLink></td>
      </tr></tbody>
    </table>
  </div>
</template>
