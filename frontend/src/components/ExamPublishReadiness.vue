<script setup>
import { onMounted, ref } from 'vue'
import { teacherApi } from '../api/teacher'
import TeacherModal from './TeacherModal.vue'

const props = defineProps({ examId: { required: true }, title: String })
const emit = defineEmits(['close', 'published'])
const readiness = ref(null), loading = ref(true), busy = ref(false), error = ref('')

async function load() {
  loading.value = true; error.value = ''
  try { readiness.value = (await teacherApi.publishReadiness(props.examId)).data }
  catch (e) { error.value = e.message }
  finally { loading.value = false }
}
async function publish() {
  if (!readiness.value?.ready || busy.value) return
  busy.value = true; error.value = ''
  try { await teacherApi.publish(props.examId); emit('published') }
  catch (e) {
    const message = e.message
    await load()
    error.value = message
  }
  finally { busy.value = false }
}
onMounted(load)
</script>

<template>
  <TeacherModal :title="`发布检查 · ${title || '考试'}`" size="confirm" :busy="busy" @close="emit('close')">
    <p v-if="loading" class="loading-state" role="status">正在检查发布条件…</p>
    <template v-else-if="readiness">
      <div class="exam-readiness-facts">
        <span>基本信息检查：{{ readiness.errors?.some(item => item.includes('所属课程') || item.includes('草稿')) ? '请查看下方问题' : '已通过' }}</span>
        <span>时间与考试规则检查：{{ readiness.errors?.some(item => item.includes('时间') || item.includes('迟到') || item.includes('交卷')) ? '请查看下方问题' : '已通过' }}</span>
        <span>试卷：{{ readiness.questionCount }} 题 / {{ readiness.totalScore }} 分</span>
        <span>参考学生：{{ readiness.eligibleStudentCount }} 人</span>
      </div>
      <div v-if="readiness.errors?.length" class="error-message" role="alert"><strong>暂不能发布</strong><ul><li v-for="item in readiness.errors" :key="item">{{ item }}</li></ul></div>
      <div v-if="readiness.warnings?.length" class="notice"><strong>提示</strong><ul><li v-for="item in readiness.warnings" :key="item">{{ item }}</li></ul></div>
      <p v-if="readiness.ready" class="muted">确认后将发布考试，试卷与考试规则随即锁定。</p>
    </template>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <template #footer><button class="secondary" :disabled="busy" @click="emit('close')">取消</button><button class="primary" :disabled="!readiness?.ready || busy" @click="publish">{{ busy ? '发布中…' : '确认发布' }}</button></template>
  </TeacherModal>
</template>
