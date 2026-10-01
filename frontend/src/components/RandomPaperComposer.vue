<script setup>
import { onMounted, ref } from 'vue'
import { teacherApi } from '../api/teacher'
import { banksForExam } from '../utils/questionBankMetadata'

const props = defineProps({ examId: [Number, String], courseId: [Number, String] })
const emit = defineEmits(['saved', 'close'])
const banks = ref([]), selectedBankIds = ref([]), singleChoiceCount = ref(0), shortAnswerCount = ref(0)
const preview = ref(null), loading = ref(false), busy = ref(false), error = ref('')

async function loadBanks() {
  try { banks.value = banksForExam((await teacherApi.questionBanks()).data, props.courseId) }
  catch (e) { error.value = e.message }
}

function toggleBank(bank, checked) {
  if (checked) selectedBankIds.value.push(bank.id)
  else selectedBankIds.value = selectedBankIds.value.filter(id => id !== bank.id)
}

function countError() {
  const single = Number(singleChoiceCount.value) || 0
  const short = Number(shortAnswerCount.value) || 0
  if (single === 0 && short === 0) return '单选题和简答题数量不能同时为 0'
  if (single + short > 100) return '随机抽题总数不能超过 100'
  return ''
}

async function roll() {
  if (!selectedBankIds.value.length) { error.value = '请至少选择一个题库'; return }
  const invalid = countError()
  if (invalid) { error.value = invalid; preview.value = null; return }
  loading.value = true; error.value = ''
  try {
    preview.value = (await teacherApi.randomPreview(props.examId, {
      bankIds: selectedBankIds.value,
      singleChoiceCount: Number(singleChoiceCount.value) || 0,
      shortAnswerCount: Number(shortAnswerCount.value) || 0,
    })).data
  } catch (e) { preview.value = null; error.value = e.message } finally { loading.value = false }
}

async function confirmAdd() {
  if (!preview.value || !preview.value.items.length) return
  busy.value = true; error.value = ''
  try {
    await teacherApi.addQuestionsFromBank(props.examId, preview.value.items.map(item => item.bankItemId))
    emit('saved', preview.value.items.length)
  } catch (e) { error.value = e.message } finally { busy.value = false }
}

onMounted(loadBanks)
</script>

<template>
  <div class="editor-form">
    <p class="muted">从多个题库中随机抽题生成预览，确认后整批加入试卷。预览不会保存，可反复重新随机。</p>
    <fieldset class="bank-picker">
      <legend>我的题库（可多选）</legend>
      <p v-if="!banks.length && !error" class="empty-state">暂无可用于组卷的题库。</p>
      <label v-for="bank in banks" :key="bank.id" class="bank-option">
        <input type="checkbox" :value="bank.id" :checked="selectedBankIds.includes(bank.id)"
               @change="toggleBank(bank, $event.target.checked)" />
        {{ bank.name }}
      </label>
    </fieldset>
    <form class="form-grid" @submit.prevent="roll">
      <label>单选题数量<input v-model.number="singleChoiceCount" type="number" min="0" max="100" step="1" required /></label>
      <label>简答题数量<input v-model.number="shortAnswerCount" type="number" min="0" max="100" step="1" required /></label>
      <button class="primary" :disabled="loading || !selectedBankIds.length">
        {{ loading ? '随机中…' : preview ? '重新随机' : '随机生成预览' }}
      </button>
    </form>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <template v-if="preview">
      <div class="meta-strip">
        <span>单选题 {{ preview.singleChoiceCount }} 道</span>
        <span>简答题 {{ preview.shortAnswerCount }} 道</span>
        <span>总计 {{ preview.totalCount }} 道</span>
        <span>预计总分 {{ preview.estimatedTotalScore }} 分</span>
      </div>
      <div v-for="item in preview.items" :key="item.bankItemId" class="surface question-card">
        <header class="section-head">
          <strong>{{ item.title }}</strong>
          <span class="actions"><span class="badge">{{ item.questionType === 'SINGLE_CHOICE' ? '单选题' : '简答题' }}</span><strong>{{ item.defaultScore }} 分</strong></span>
        </header>
        <p class="preserve">{{ item.content }}</p>
        <p class="muted">来源题库：{{ item.bankName }}</p>
      </div>
      <div class="section-head">
        <strong>已抽中 {{ preview.totalCount }} 道题</strong>
        <span class="actions">
          <button type="button" class="secondary" :disabled="busy || loading" @click="roll">重新随机</button>
          <button type="button" class="ghost" :disabled="busy" @click="emit('close')">取消</button>
          <button type="button" class="primary" :disabled="busy" @click="confirmAdd">{{ busy ? '加入中…' : '确认加入试卷' }}</button>
        </span>
      </div>
    </template>
  </div>
</template>
