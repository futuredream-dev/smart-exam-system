<script setup>
import { computed, ref } from 'vue'
import { teacherApi } from '../api/teacher'
import { difficultyNames } from '../utils/questionBankMetadata'
import { canConfirmBankImport, importPreviewPage } from '../utils/questionBankImport'

const props = defineProps({ bankId: { type: Number, required: true } })
const emit = defineEmits(['imported'])
const file = ref(null), preview = ref(null), result = ref(null)
const error = ref(''), busy = ref(false), downloading = ref(false)
const filter = ref('ALL'), page = ref(1), duplicatePolicy = ref('')
const pageData = computed(() => importPreviewPage(preview.value, filter.value, page.value))
const canConfirm = computed(() => !busy.value && !!file.value && canConfirmBankImport(preview.value, duplicatePolicy.value))
const typeNames = { SINGLE_CHOICE: '单选题', SHORT_ANSWER: '简答题' }
const statusNames = { VALID: '可导入', ERROR: '错误', DUPLICATE: '重复题' }
const sourceNames = { BANK: '与题库已有题目重复', FILE: '与文件内题目重复' }

function selectFile(event) {
  file.value = event.target.files?.[0] || null
  preview.value = null
  result.value = null
  error.value = ''
  duplicatePolicy.value = ''
  filter.value = 'ALL'
  page.value = 1
}
function changeFilter(value) { filter.value = value; page.value = 1 }
async function downloadTemplate() {
  downloading.value = true; error.value = ''
  try {
    const blob = await teacherApi.bankImportTemplate(props.bankId)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url; link.download = '题库导入模板.xlsx'
    document.body.appendChild(link); link.click(); link.remove()
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (e) { error.value = e.message || '模板下载失败' }
  finally { downloading.value = false }
}
async function previewFile() {
  if (!file.value || busy.value) return
  preview.value = null; result.value = null; error.value = ''; busy.value = true
  try {
    preview.value = (await teacherApi.previewBankImport(props.bankId, file.value)).data
    duplicatePolicy.value = ''
    filter.value = preview.value.errorCount ? 'ERROR' : 'ALL'
    page.value = 1
  } catch (e) { error.value = e.message || '预览失败' }
  finally { busy.value = false }
}
async function confirmImport() {
  if (!canConfirm.value) return
  busy.value = true; error.value = ''
  try {
    result.value = (await teacherApi.confirmBankImport(props.bankId, file.value, duplicatePolicy.value || 'SKIP')).data
    preview.value = null
    emit('imported', result.value)
  } catch (e) {
    preview.value = null
    error.value = `${e.message || '导入失败'}；请重新预览后再试`
  }
  finally { busy.value = false }
}
</script>

<template>
  <div class="bank-import-panel">
    <p class="muted">按模板填写 .xlsx 文件，最多 500 道题、1MB。预览只校验，不写入题库；确认时会重新校验文件。</p>
    <button class="ghost" type="button" :disabled="downloading || busy" @click="downloadTemplate">{{ downloading ? '下载中…' : '下载 Excel 模板' }}</button>
    <label class="bank-import-file">选择文件
      <input type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" :disabled="busy" @change="selectFile" />
    </label>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <div class="actions"><button class="secondary" type="button" :disabled="!file || busy" @click="previewFile">{{ busy && !result ? '处理中…' : '预览统计与逐行校验' }}</button></div>

    <template v-if="preview">
      <h3>预览统计</h3>
      <div class="bank-import-stats" role="status">
        <span>试题 {{ preview.totalRows }}</span><span>可导入 {{ preview.validCount }}</span>
        <span>错误 {{ preview.errorCount }}</span><span>重复题 {{ preview.duplicateCount }}</span>
        <span>单选 {{ preview.singleChoiceCount }}</span><span>简答 {{ preview.shortAnswerCount }}</span>
      </div>
      <p v-if="Object.keys(preview.difficultyCounts || {}).length" class="muted">难度：<span v-for="(count, key) in preview.difficultyCounts" :key="key">{{ difficultyNames[key] || key }} {{ count }} 道　</span></p>
      <p v-if="preview.errorCount" class="error-message">有 {{ preview.errorCount }} 行错误。请修改 Excel 后重新上传并预览；本次不能导入任何题目。</p>
      <div class="bank-import-filters" aria-label="预览行筛选">
        <button v-for="choice in [{ value: 'ALL', label: '全部', count: preview.rows.length }, { value: 'ERROR', label: '错误', count: preview.errorCount }, { value: 'DUPLICATE', label: '重复题', count: preview.duplicateCount }]" :key="choice.value" type="button" :class="{ active: filter === choice.value }" :aria-pressed="filter === choice.value" @click="changeFilter(choice.value)">{{ choice.label }}（{{ choice.count }}）</button>
      </div>
      <div v-if="pageData.total" class="table-scroll bank-import-table"><table><thead><tr><th>Excel 行</th><th>题目编号</th><th>题型</th><th>题干</th><th>状态与原因</th></tr></thead><tbody>
        <tr v-for="row in pageData.rows" :key="`${row.rowNumber}-${row.questionNumber}`">
          <td>{{ row.rowNumber }}</td><td>{{ row.questionNumber || '—' }}</td><td>{{ typeNames[row.questionType] || row.questionType || '—' }}</td>
          <td class="bank-import-content">{{ row.content || '—' }}</td>
          <td><strong :class="{ 'bank-import-error': row.status === 'ERROR' }">{{ statusNames[row.status] || row.status }}</strong><span v-if="row.duplicateSource" class="bank-import-reason">{{ sourceNames[row.duplicateSource] }}</span><ul v-if="row.errors?.length"><li v-for="(message, index) in row.errors" :key="index">{{ message }}</li></ul></td>
        </tr>
      </tbody></table></div>
      <p v-else class="muted">此筛选下没有题目。</p>
      <div v-if="pageData.pages > 1" class="bank-import-pagination"><span>第 {{ pageData.page }} / {{ pageData.pages }} 页，共 {{ pageData.total }} 行；每页 50 行</span><button class="secondary" type="button" :disabled="pageData.page <= 1" @click="page--">上一页</button><button class="secondary" type="button" :disabled="pageData.page >= pageData.pages" @click="page++">下一页</button></div>

      <fieldset v-if="preview.duplicateCount && !preview.errorCount" class="bank-import-policy"><legend>选择重复题策略</legend><label><input v-model="duplicatePolicy" type="radio" value="SKIP" name="bank-import-policy" />跳过重复题（预计导入 {{ preview.validCount }} 道）</label><label><input v-model="duplicatePolicy" type="radio" value="ALLOW" name="bank-import-policy" />允许重复题一并导入（预计导入 {{ preview.validCount + preview.duplicateCount }} 道）</label></fieldset>
      <p v-if="!preview.errorCount" class="muted">确认时将重新校验原文件和当前题库；若出现错误，整批不会导入。</p>
      <button class="primary" type="button" :disabled="!canConfirm" @click="confirmImport">{{ busy ? '导入中…' : '确认导入' }}</button>
    </template>

    <section v-if="result" class="bank-import-result" aria-label="导入结果" role="status"><h3>导入结果</h3><div class="bank-import-stats"><span>文件题目 {{ result.totalRows }}</span><span>成功导入 {{ result.importedCount }}</span><span>跳过重复题 {{ result.skippedCount }}</span></div><p>关闭窗口后可在题库列表查看导入的题目。</p></section>
  </div>
</template>
