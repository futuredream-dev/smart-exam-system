<script setup>
import { computed, ref, watch } from 'vue'
import { teacherApi } from '../api/teacher'
import { activeChapterOptions, batchMetadataPayload, difficultyNames } from '../utils/questionBankMetadata'

const props = defineProps({ bankId: [Number, String], itemIds: { type: Array, required: true }, metadata: { type: Object, required: true } })
const emit = defineEmits(['saved'])
const operation = ref(''), value = ref(''), values = ref([]), reviewing = ref(false), busy = ref(false), error = ref('')
const allPoints = ref([])
const chapters = computed(() => activeChapterOptions(props.metadata.chapters || []))
const points = computed(() => (props.metadata.knowledgePoints || []).filter(row => row.status === 'ACTIVE'))
const operationNames = {
  SET_CHAPTER: '设置章节', CLEAR_CHAPTER: '清除章节', SET_DIFFICULTY: '设置难度', CLEAR_DIFFICULTY: '清除难度',
  ADD_KNOWLEDGE: '添加知识点', REMOVE_KNOWLEDGE: '移除知识点', ADD_TAG: '添加标签', REMOVE_TAG: '移除标签',
}
const relationOperation = computed(() => ['ADD_KNOWLEDGE','REMOVE_KNOWLEDGE','ADD_TAG','REMOVE_TAG'].includes(operation.value))
const relationChoices = computed(() => operation.value.includes('KNOWLEDGE')
  ? (operation.value === 'REMOVE_KNOWLEDGE' && allPoints.value.length ? allPoints.value : points.value)
  : (props.metadata.tags || []))
const summary = computed(() => {
  if (operation.value === 'SET_CHAPTER') return chapters.value.find(row => row.id === Number(value.value))?.label || ''
  if (operation.value === 'SET_DIFFICULTY') return difficultyNames[value.value] || ''
  if (relationOperation.value) return values.value.map(id => relationChoices.value.find(row => row.id === id)?.name).filter(Boolean).join('、')
  return '原有其他分类信息保持不变'
})
watch(operation, async () => {
  value.value = ''; values.value = []; reviewing.value = false; error.value = ''
  if (operation.value === 'REMOVE_KNOWLEDGE' && !allPoints.value.length) {
    try { allPoints.value = (await teacherApi.bankMetadata(props.bankId, true)).data.knowledgePoints }
    catch (e) { error.value = e.message || '知识点加载失败' }
  }
})
watch([value, values], () => { reviewing.value = false }, { deep: true })
function review() {
  error.value = ''
  if (!operation.value) { error.value = '请选择批量操作'; return }
  if (props.itemIds.length < 1 || props.itemIds.length > 200) { error.value = '一次只能处理 1 到 200 道题'; return }
  if ((operation.value === 'SET_CHAPTER' || operation.value === 'SET_DIFFICULTY') && value.value === '') { error.value = '请选择目标值'; return }
  if (relationOperation.value && !values.value.length) { error.value = '请选择至少一项'; return }
  reviewing.value = true
}
async function submit() {
  if (!reviewing.value || busy.value) return
  error.value = ''; busy.value = true
  try {
    const payload = batchMetadataPayload(props.itemIds, operation.value, relationOperation.value ? values.value : value.value)
    await teacherApi.batchBankItemMetadata(props.bankId, payload)
    emit('saved', props.itemIds.length)
  } catch (e) { error.value = e.message || '批量操作失败'; reviewing.value = false }
  finally { busy.value = false }
}
</script>

<template>
  <div class="bank-batch-editor">
    <p class="bank-batch-count">将修改 <strong>{{ itemIds.length }}</strong> 道题（当前明确选中的题目）</p>
    <label>批量操作<select v-model="operation"><option value="">请选择</option><option v-for="(name, key) in operationNames" :key="key" :value="key">{{ name }}</option></select></label>
    <label v-if="operation === 'SET_CHAPTER'">目标章节<select v-model="value"><option value="">请选择章节</option><option v-for="chapter in chapters" :key="chapter.id" :value="chapter.id">{{ chapter.label }}</option></select></label>
    <label v-if="operation === 'SET_DIFFICULTY'">目标难度<select v-model="value"><option value="">请选择难度</option><option value="EASY">简单</option><option value="MEDIUM">中等</option><option value="HARD">困难</option></select></label>
    <div v-if="relationOperation"><strong>{{ operation.includes('KNOWLEDGE') ? '知识点' : '标签' }}</strong><div class="bank-choice-list"><label v-for="entry in relationChoices" :key="entry.id"><input v-model="values" type="checkbox" :value="entry.id" />{{ entry.name }}{{ entry.status === 'ARCHIVED' ? '（已归档）' : '' }}</label><span v-if="!relationChoices.length" class="muted">暂无可选项</span></div></div>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <button v-if="!reviewing" type="button" class="primary" @click="review">查看变更</button>
    <div v-else class="bank-batch-review"><strong>确认变更</strong><p>{{ operationNames[operation] }}：{{ summary }}</p><p>将修改 {{ itemIds.length }} 道题。其余分类信息保持不变。</p><button type="button" class="primary" :disabled="busy" @click="submit">{{ busy ? '提交中…' : `确认修改 ${itemIds.length} 道题` }}</button></div>
  </div>
</template>
