<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { teacherApi, formatTime } from '../../api/teacher'
import ExamList from '../../components/ExamList.vue'
import ExamForm from '../../components/ExamForm.vue'
import TeacherModal from '../../components/TeacherModal.vue'
const route = useRoute(), router = useRouter()
const course = ref(null), exams = ref([]), enrolledStudents = ref([]), allStudents = ref([])
const error = ref(''), notice = ref(''), loading = ref(true), opened = ref(false), busy = ref(false), selectedStudentId = ref('')
const importOpened = ref(false), importFile = ref(null), preview = ref(null), importResult = ref(null), importBusy = ref(false)
const usernameColumn = ref(null), nameColumn = ref(null)
const resetResult = ref(null), resetBusyId = ref(null)
const resetTarget = ref(null)
const availableStudents = computed(() => allStudents.value.filter(student => !enrolledStudents.value.some(row => row.id === student.id)))
async function load() {
  try { const [c,e,s,a] = await Promise.all([teacherApi.course(route.params.courseId), teacherApi.exams(route.params.courseId), teacherApi.courseStudents(route.params.courseId), teacherApi.students()]); course.value = c.data; exams.value = e.data; enrolledStudents.value = s.data; allStudents.value = a.data }
  catch(e) { error.value = e.message } finally { loading.value = false }
}
async function addStudent() {
  if (!selectedStudentId.value) return
  busy.value = true; error.value = ''; notice.value = ''
  try { await teacherApi.addCourseStudent(course.value.id, selectedStudentId.value); selectedStudentId.value = ''; notice.value = '学生已加入课程'; await load() }
  catch(e) { error.value = e.message } finally { busy.value = false }
}
async function removeStudent(student) {
  if (!window.confirm(`确定将“${student.name}”移出课程吗？`)) return
  busy.value = true; error.value = ''; notice.value = ''
  try { await teacherApi.removeCourseStudent(course.value.id, student.id); notice.value = '学生已移出课程'; await load() }
  catch(e) { error.value = e.message } finally { busy.value = false }
}
function askResetPassword(student) { if (resetBusyId.value === null) { error.value = ''; resetTarget.value = student } }
async function resetPassword() {
  if (!resetTarget.value || resetBusyId.value !== null) return
  const student = resetTarget.value
  resetBusyId.value = student.id; error.value = ''
  try {
    const result = (await teacherApi.resetStudentPassword(course.value.id, student.id)).data
    resetTarget.value = null; resetBusyId.value = null
    // 先关闭确认弹窗并恢复入口焦点，再交接到一次性密码结果弹窗。
    await nextTick()
    resetResult.value = result
  }
  catch (e) { error.value = e.message }
  finally { resetBusyId.value = null }
}
function closeResetResult() { resetResult.value = null }
function saveBlob(blob, filename) {
  const url = URL.createObjectURL(blob), link = document.createElement('a')
  link.href = url; link.download = filename; document.body.appendChild(link); link.click(); link.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}
async function downloadTemplate() {
  error.value = ''
  try { saveBlob(await teacherApi.studentImportTemplate(course.value.id), '学生账号导入模板.xlsx') }
  catch (e) { error.value = e.message }
}
function chooseImportFile(event) {
  importFile.value = event.target.files?.[0] || null; preview.value = null; importResult.value = null
  usernameColumn.value = null; nameColumn.value = null
}
async function previewImport() {
  if (!importFile.value) return
  importBusy.value = true; error.value = ''
  try {
    preview.value = (await teacherApi.previewStudentImport(course.value.id, importFile.value, usernameColumn.value, nameColumn.value)).data
    usernameColumn.value = preview.value.usernameColumn
    nameColumn.value = preview.value.nameColumn
  }
  catch (e) { error.value = e.message }
  finally { importBusy.value = false }
}
async function confirmImport() {
  if (!importFile.value || !preview.value || preview.value.mappingRequired) return
  importBusy.value = true; error.value = ''
  try {
    importResult.value = (await teacherApi.importStudents(course.value.id, importFile.value, usernameColumn.value, nameColumn.value)).data
    preview.value = null; notice.value = '批量导入完成，请及时下载并妥善保管初始密码'; await load()
  } catch (e) { error.value = e.message }
  finally { importBusy.value = false }
}
function downloadImportResult() {
  if (!importResult.value) return
  const binary = window.atob(importResult.value.resultWorkbookBase64)
  const bytes = Uint8Array.from(binary, char => char.charCodeAt(0))
  saveBlob(new Blob([bytes], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }), '学生账号导入结果.xlsx')
}
function closeImport() {
  importOpened.value = false; importFile.value = null; preview.value = null; importResult.value = null
  usernameColumn.value = null; nameColumn.value = null
}
onMounted(load)
</script>
<template>
  <RouterLink to="/teacher/courses">← 返回课程管理</RouterLink>
  <p v-if="error && !resetTarget" class="error-message" role="alert">{{ error }}</p><p v-if="notice" class="success-message" role="status">{{ notice }}</p><p v-if="loading" class="loading-state" role="status">正在加载课程…</p>
  <template v-if="course">
    <header class="page-heading"><div><h1>{{ course.courseName }}</h1><p class="muted preserve">{{ course.description || '暂无课程描述' }}</p></div><button class="primary" @click="opened = true">创建考试</button></header>
    <div class="surface meta-strip"><span>授课教师：{{ course.teacherName }}</span><span>创建时间：{{ formatTime(course.createdAt) }}</span><span>{{ exams.length }} 场考试</span></div>
    <section class="surface">
      <div class="section-head"><h2>学生管理 <span class="muted">已加入 {{ enrolledStudents.length }} 人</span></h2><div class="actions"><button class="ghost" :disabled="busy" @click="downloadTemplate">下载 Excel 模板</button><button class="secondary" :disabled="busy" @click="importOpened = true">批量导入</button><select v-model.number="selectedStudentId" aria-label="选择系统学生" :disabled="busy || !availableStudents.length"><option value="">{{ availableStudents.length ? '选择系统学生' : '暂无可添加学生' }}</option><option v-for="student in availableStudents" :key="student.id" :value="student.id">{{ student.name }}（{{ student.username }}）</option></select><button class="secondary" :disabled="busy || !selectedStudentId" @click="addStudent">添加学生</button></div></div>
      <p v-if="!enrolledStudents.length" class="empty-state">当前课程还没有学生。</p>
      <div v-else class="table-scroll"><table><thead><tr><th>姓名</th><th>账号</th><th>操作</th></tr></thead><tbody><tr v-for="student in enrolledStudents" :key="student.id"><td>{{ student.name }}</td><td>{{ student.username }}</td><td><div class="actions"><button class="secondary" :disabled="busy || resetBusyId !== null" @click="askResetPassword(student)">{{ resetBusyId === student.id ? '重置中…' : '重置密码' }}</button><button class="danger" :disabled="busy" @click="removeStudent(student)">移出课程</button></div></td></tr></tbody></table></div>
    </section>
    <section class="surface"><h2>课程考试</h2><ExamList :exams="exams" /></section>
    <TeacherModal v-if="opened" title="创建考试" @close="opened = false"><ExamForm :course-id="course.id" @saved="e => router.push('/teacher/exams/' + e.id)" /></TeacherModal>
    <TeacherModal v-if="importOpened" title="Excel 批量导入学生" @close="closeImport">
      <div class="import-panel">
        <p class="muted">支持上传已有的 .xlsx 名单。系统会识别常见的学号和姓名表头，其他列自动忽略；预览不会写入数据库，确认时会重新校验原文件。</p>
        <input type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" :disabled="importBusy || !!importResult" @change="chooseImportFile">
        <div v-if="!importResult" class="actions">
          <button class="secondary" :disabled="importBusy || !importFile" @click="previewImport">{{ importBusy ? '校验中…' : '预览校验' }}</button>
          <button class="primary" :disabled="importBusy || !preview || preview.mappingRequired" @click="confirmImport">{{ importBusy ? '导入中…' : '确认导入' }}</button>
        </div>
        <template v-if="preview?.mappingRequired">
          <div class="mapping-grid">
            <label>哪一列作为学号
              <select v-model.number="usernameColumn"><option :value="null">请选择</option><option v-for="header in preview.headers" :key="header.index" :value="header.index">{{ header.name }}</option></select>
            </label>
            <label>哪一列作为姓名
              <select v-model.number="nameColumn"><option :value="null">请选择</option><option v-for="header in preview.headers" :key="header.index" :value="header.index">{{ header.name }}</option></select>
            </label>
          </div>
          <p v-if="usernameColumn === nameColumn && usernameColumn !== null" class="error-message">学号列和姓名列不能选择同一列。</p>
          <button class="secondary" :disabled="importBusy || usernameColumn === null || nameColumn === null || usernameColumn === nameColumn" @click="previewImport">按选择重新预览</button>
        </template>
        <template v-if="preview && !preview.mappingRequired">
          <h3>导入预览（{{ preview.totalRows }} 行）</h3>
          <div class="table-scroll"><table><thead><tr><th>行号</th><th>学号</th><th>姓名</th><th>状态</th></tr></thead><tbody><tr v-for="row in preview.rows" :key="row.rowNumber"><td>{{ row.rowNumber }}</td><td>{{ row.username || '—' }}</td><td>{{ row.name || '—' }}</td><td>{{ row.statusText }}</td></tr></tbody></table></div>
        </template>
        <template v-if="importResult">
          <div class="meta-strip"><span>新建账号：{{ importResult.createdCount }}</span><span>已有学生加入：{{ importResult.enrolledExistingCount }}</span><span>跳过：{{ importResult.skippedCount }}</span><span>失败：{{ importResult.failedCount }}</span></div>
          <p class="error-message">初始密码只在本页面显示一次。关闭窗口前请下载结果，之后无法找回。</p>
          <div class="table-scroll"><table><thead><tr><th>学号/账号</th><th>姓名</th><th>初始密码</th><th>导入状态</th></tr></thead><tbody><tr v-for="row in importResult.rows" :key="row.rowNumber"><td>{{ row.username || '—' }}</td><td>{{ row.name || '—' }}</td><td>{{ row.initialPassword || '—' }}</td><td>{{ row.statusText }}</td></tr></tbody></table></div>
          <div class="actions"><button class="primary" @click="downloadImportResult">下载导入结果</button><button class="ghost" @click="closeImport">完成并关闭</button></div>
        </template>
      </div>
    </TeacherModal>
    <TeacherModal v-if="resetTarget" title="确认重置学生密码" size="confirm" variant="danger" :busy="resetBusyId !== null" @close="resetTarget = null">
      <div class="confirmation-copy"><p><strong>{{ resetTarget.name }}</strong>（{{ resetTarget.username }}）</p><p>原密码将立即失效，并生成一次性临时密码。学生下次登录需要修改密码；临时密码关闭后无法再次查看。</p><p v-if="error" class="error-message" role="alert">{{ error }}</p></div>
      <template #footer><button class="secondary" :disabled="resetBusyId !== null" @click="resetTarget = null">取消</button><button class="danger" :disabled="resetBusyId !== null" @click="resetPassword">{{ resetBusyId !== null ? '重置中…' : '重置并生成临时密码' }}</button></template>
    </TeacherModal>
    <TeacherModal v-if="resetResult" title="临时密码（仅显示一次）" @close="closeResetResult">
      <div class="import-panel">
        <p>{{ resetResult.name }}（{{ resetResult.username }}）的临时密码：</p>
        <p class="temporary-password">{{ resetResult.temporaryPassword }}</p>
        <p class="error-message">该密码只显示一次。关闭窗口前请妥善记录，学生登录后必须立即修改密码。</p>
        <button class="primary" @click="closeResetResult">我已保存并关闭</button>
      </div>
    </TeacherModal>
  </template>
</template>
