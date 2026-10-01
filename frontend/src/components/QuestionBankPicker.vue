<script setup>
import { onMounted, ref } from 'vue'
import { teacherApi } from '../api/teacher'
import { banksForExam } from '../utils/questionBankMetadata'

const props = defineProps({ examId: [Number, String], courseId: [Number, String] })
const emit = defineEmits(['saved'])
const banks = ref([]), bankId = ref(''), keyword = ref(''), questionType = ref('')
const items = ref([]), selectedIds = ref([]), loading = ref(false), busy = ref(false), error = ref('')

async function loadBanks() {
  try {
    banks.value = banksForExam((await teacherApi.questionBanks()).data, props.courseId)
    if (banks.value.length) { bankId.value = String(banks.value[0].id); await loadItems() }
  } catch (e) { error.value = e.message }
}

async function loadItems() {
  items.value = []; selectedIds.value = []; error.value = ''
  if (!bankId.value) return
  loading.value = true
  try {
    items.value = (await teacherApi.bankCandidates(props.examId, {
      bankId: bankId.value,
      keyword: keyword.value.trim() || undefined,
      questionType: questionType.value || undefined,
    })).data
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

function toggle(item, checked) {
  if (item.alreadyAdded) return
  if (checked) selectedIds.value.push(item.id)
  else selectedIds.value = selectedIds.value.filter(id => id !== item.id)
}

async function addSelected() {
  if (!selectedIds.value.length) return
  busy.value = true; error.value = ''
  try {
    await teacherApi.addQuestionsFromBank(props.examId, selectedIds.value)
    emit('saved', selectedIds.value.length)
  } catch (e) {
    const message = e.message
    await loadItems()
    error.value = message
  } finally { busy.value = false }
}

onMounted(loadBanks)
</script>

<template>
  <div class="editor-form">
    <label>我的题库
      <select v-model="bankId" @change="loadItems">
        <option value="">请选择题库</option>
        <option v-for="bank in banks" :key="bank.id" :value="String(bank.id)">{{ bank.name }}</option>
      </select>
    </label>
    <form class="form-grid" @submit.prevent="loadItems">
      <label>关键词<input v-model="keyword" placeholder="内部名称 / 题干" maxlength="160" /></label>
      <label>题型
        <select v-model="questionType">
          <option value="">全部</option>
          <option value="SINGLE_CHOICE">单选题</option>
          <option value="SHORT_ANSWER">简答题</option>
        </select>
      </label>
      <button class="secondary" :disabled="loading || !bankId">{{ loading ? '查询中…' : '搜索' }}</button>
    </form>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <p v-if="!banks.length && !error" class="empty-state">暂无可用于组卷的题库。</p>
    <p v-else-if="bankId && !loading && !items.length" class="empty-state">当前条件下没有可用题目。</p>
    <div v-for="item in items" :key="item.id" class="surface question-card">
      <label class="section-head">
        <span class="actions">
          <input type="checkbox" :checked="selectedIds.includes(item.id)" :disabled="item.alreadyAdded"
                 @change="toggle(item, $event.target.checked)" />
          <strong>{{ item.title }}</strong>
        </span>
        <span v-if="item.alreadyAdded" class="badge">已加入</span>
      </label>
      <p class="preserve">{{ item.content }}</p>
      <div class="actions"><span class="badge">{{ item.questionType === 'SINGLE_CHOICE' ? '单选题' : '简答题' }}</span><strong>{{ item.defaultScore }} 分</strong></div>
    </div>
    <div class="section-head">
      <strong>已选择 {{ selectedIds.length }} 道题</strong>
      <button type="button" class="primary" :disabled="busy || !selectedIds.length" @click="addSelected">
        {{ busy ? '加入中…' : '加入试卷' }}
      </button>
    </div>
  </div>
</template>
