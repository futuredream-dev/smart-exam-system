<script setup>
import { computed, onMounted, ref } from 'vue'
import { teacherApi, formatTime } from '../../api/teacher'
import TeacherModal from '../../components/TeacherModal.vue'
const rows = ref([]), courses = ref([]), loading = ref(true), busy = ref(false), error = ref(''), notice = ref(''), opened = ref(false), editing = ref(null)
const binding = ref(null), bindCourseId = ref(''), bindConfirm = ref(false)
const form = ref({ name: '', description: '', courseId: '' })
const status = ref('ACTIVE'), archiving = ref(null)
const myBanks = computed(() => rows.value.filter(row => row.scope === 'TEACHER'))
const platformBanks = computed(() => rows.value.filter(row => row.scope === 'PLATFORM'))
const freeCourses = computed(() => courses.value)
const selectedCourse = computed(() => freeCourses.value.find(course => course.id === Number(bindCourseId.value)))
async function load() { loading.value = true; error.value = ''; try { const [bankResponse, courseResponse] = await Promise.all([teacherApi.questionBanks({status: status.value}), teacherApi.availableBankCourses()]); rows.value = bankResponse.data; courses.value = courseResponse.data } catch (e) { error.value = e.message } finally { loading.value = false } }
function open(row) { editing.value = row; form.value = { name: row?.name || '', description: row?.description || '', courseId: '' }; error.value = ''; opened.value = true }
async function save() { if (!form.value.name.trim()) { error.value = '题库名称不能为空'; return } if (!editing.value && !form.value.courseId) { error.value = '请选择所属课程'; return } busy.value = true; try { await teacherApi.saveQuestionBank(editing.value?.id, editing.value ? { name: form.value.name, description: form.value.description } : { ...form.value, courseId: Number(form.value.courseId) }); opened.value = false; notice.value = '题库保存成功'; await load() } catch (e) { error.value = e.message } finally { busy.value = false } }
function openBind(row) { binding.value = row; bindCourseId.value = ''; bindConfirm.value = false; error.value = '' }
async function bind() { if (!selectedCourse.value) { error.value = '请选择所属课程'; return } busy.value = true; try { await teacherApi.bindQuestionBankCourse(binding.value.id, selectedCourse.value.id); binding.value = null; notice.value = '课程绑定成功'; await load() } catch (e) { error.value = e.message } finally { busy.value = false } }
async function archive(row) { if (busy.value) return; busy.value = true; try { await teacherApi.archiveQuestionBank(row.id); archiving.value = null; notice.value = '题库已归档，可在“已归档”中恢复；课程绑定保持不变。'; await load() } catch (e) { error.value = e.message } finally { busy.value = false } }
async function restore(row) { if (busy.value) return; busy.value = true; error.value = ''; try { await teacherApi.restoreQuestionBank(row.id); notice.value = '题库已恢复，所属课程与题目状态保持不变。'; await load() } catch(e) { error.value = e.message } finally { busy.value = false } }
onMounted(load)
</script>
<template>
  <header class="page-heading"><div><h1>题库管理</h1><p class="muted">维护个人题库，并可查看平台公共题库用于组卷。</p></div><button class="primary" @click="open(null)">+ 新建题库</button></header>
  <label>题库状态 <select v-model="status" :disabled="loading||busy" @change="notice='';load()"><option value="ACTIVE">使用中</option><option value="ARCHIVED">已归档</option><option value="ALL">全部</option></select></label>
  <p v-if="notice" class="success-message" role="status">{{ notice }}</p><p v-if="error && !opened" class="error-message" role="alert">{{ error }}</p>
  <p v-if="loading" class="loading-state" role="status">正在加载题库…</p>
  <template v-else>
    <div class="section-head"><h2>我的题库</h2><span class="muted">个人维护，可用于组卷</span></div>
    <p v-if="!error && !myBanks.length" class="empty-state">{{ status==='ARCHIVED'?'暂无已归档的个人题库。':'当前筛选下没有个人题库，可切换状态或新建题库。' }}</p>
    <div v-else class="course-grid"><article v-for="row in myBanks" :key="row.id" class="surface course-card">
      <div class="teacher-card-heading"><div class="course-icon">题</div><h2>{{ row.name }}</h2><span v-if="row.status==='ARCHIVED'" class="badge">已归档</span></div><p class="muted preserve card-description">{{ row.description || '暂无说明' }}</p>
      <div class="course-meta"><span>所属课程：{{ row.courseName || '未绑定课程' }}</span><span>{{ row.questionCount }} 道题目</span><small>{{ formatTime(row.updatedAt) }} 更新</small></div>
      <footer class="actions"><RouterLink class="secondary link-button" :to="'/teacher/question-banks/' + row.id">{{ row.status==='ARCHIVED'?'查看题库':'进入题库' }}</RouterLink><button v-if="row.status==='ARCHIVED'" class="primary" :disabled="busy" @click="restore(row)">恢复题库</button><template v-else><button class="ghost" @click="open(row)">编辑</button><button class="secondary" @click="openBind(row)">{{ row.courseId ? '更换课程' : '绑定课程' }}</button><button class="danger" :disabled="busy" @click="archiving=row">归档</button></template></footer>
    </article></div>
    <template v-if="platformBanks.length">
      <div class="section-head"><h2>平台题库</h2><span class="muted">平台公共维护，仅可查看和用于组卷</span></div>
      <div class="course-grid"><article v-for="row in platformBanks" :key="row.id" class="surface course-card">
        <div class="teacher-card-heading"><div class="course-icon">公</div><h2>{{ row.name }}</h2></div><p class="muted preserve card-description">{{ row.description || '暂无说明' }}</p>
        <div class="course-meta"><span>平台公共</span><span>{{ row.questionCount }} 道题目</span><small>{{ formatTime(row.updatedAt) }} 更新</small></div>
        <footer class="actions"><RouterLink class="secondary link-button" :to="'/teacher/question-banks/' + row.id">进入题库</RouterLink></footer>
      </article></div>
    </template>
  </template>
  <TeacherModal v-if="archiving" title="归档题库" size="confirm" :busy="busy" @close="archiving=null"><div class="editor-form">
    <p>确定归档题库“{{archiving.name}}”吗？</p><p>归档后保留课程绑定、题目状态与历史试卷，在“已归档”中可以恢复。</p>
    <p v-if="error" class="error-message" role="alert">{{error}}</p><div class="actions"><button :disabled="busy" @click="archiving=null">取消</button><button class="danger" :disabled="busy" @click="archive(archiving)">{{busy?'归档中…':'确认归档'}}</button></div>
  </div></TeacherModal>
  <TeacherModal v-if="opened" :title="editing ? '编辑题库' : '新建题库'" @close="!busy && (opened = false)"><form class="editor-form" @submit.prevent="save">
    <label>题库名称<input v-model="form.name" maxlength="160" required /></label><label>说明<textarea v-model="form.description" maxlength="10000" rows="4" /></label>
    <label v-if="!editing">所属课程 *<select v-model="form.courseId" required><option value="">请选择课程</option><option v-for="course in freeCourses" :key="course.id" :value="course.id">{{ course.courseName }}</option></select></label>
    <p v-if="!editing && !freeCourses.length" class="empty-state">当前没有可绑定的课程，请先创建课程或处理已有题库。</p>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p><button class="primary" :disabled="busy">{{ busy ? '保存中…' : '保存题库' }}</button>
  </form></TeacherModal>
  <TeacherModal v-if="binding" :title="binding.courseId ? '更换课程' : '绑定课程'" @close="!busy && (binding = null)"><div class="editor-form">
    <p>当前题库：{{ binding.name }}</p><p>当前课程：{{ binding.courseName || '未绑定课程' }}</p>
    <label>选择课程<select v-model="bindCourseId" @change="bindConfirm = false"><option value="">请选择课程</option><option v-for="course in freeCourses" :key="course.id" :value="course.id">{{ course.courseName }}</option></select></label>
    <p v-if="!freeCourses.length" class="empty-state">当前没有可绑定的课程，请先创建课程或处理已有题库。</p>
    <p class="muted">每个题库只属于一门课程，每门课程最多绑定一个题库。已用于考试的题库不能更换课程。</p>
    <p v-if="selectedCourse">将“{{ binding.name }}”绑定到“{{ selectedCourse.courseName }}”</p>
    <label v-if="selectedCourse"><input v-model="bindConfirm" type="checkbox" />确认建立此一对一关系</label>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <div class="actions"><button class="ghost" :disabled="busy" @click="binding = null">取消</button><button class="primary" :disabled="busy || !bindConfirm || !selectedCourse" @click="bind">{{ busy ? '绑定中…' : '确认绑定' }}</button></div>
  </div></TeacherModal>
</template>
