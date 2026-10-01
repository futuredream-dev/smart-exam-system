<script setup>
import { onMounted, ref } from 'vue'
import { adminApi } from '../../api/admin'
const summary = ref(null), error = ref('')
onMounted(async () => { try { summary.value = (await adminApi.dashboard()).data } catch (e) { error.value = e.message } })
const statusNames = { DRAFT: '草稿', PUBLISHED: '已发布', GRADING: '阅卷中', RELEASED: '已发布成绩' }
</script>

<template>
  <div class="admin-workspace">
    <header class="page-heading"><div><h1>系统概览</h1><p class="muted">平台账号与教学资源总览。</p></div></header>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <p v-else-if="!summary" class="empty-state">正在加载概览…</p>
    <template v-else>
      <section class="admin-metrics" aria-label="平台统计">
        <div v-for="item in [['教师', summary.teacherCount], ['学生', summary.studentCount], ['课程', summary.courseCount], ['考试', summary.examCount]]" :key="item[0]" class="surface"><span>{{ item[0] }}</span><strong>{{ item[1] }}</strong></div>
      </section>
      <section class="surface"><h2>账号状态</h2><p>启用 {{ summary.activeUserCount }} · 停用 {{ summary.disabledUserCount }}</p></section>
      <section class="surface"><h2>考试业务状态</h2><div class="actions"><span v-for="(count, key) in summary.examStatuses" :key="key" class="badge">{{ statusNames[key] || key }} {{ count }}</span></div></section>
    </template>
  </div>
</template>
