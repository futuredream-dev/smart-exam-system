<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { teacherApi } from '../api/teacher'

const props = defineProps({ bankId: [Number, String] })
const emit = defineEmits(['changed'])
const tab = ref('chapters'), metadata = ref({ chapters: [], knowledgePoints: [], tags: [] })
const error = ref(''), notice = ref(''), busy = ref(false), search = ref('')
const chapterForm = reactive({ id: null, name: '', parentId: '', sortOrder: 0 })
const pointForm = reactive({ id: null, name: '', description: '' })
const tagForm = reactive({ id: null, name: '' })
const roots = computed(() => metadata.value.chapters.filter(row => row.parentId == null).sort(byOrder))
const rootOptions = computed(() => roots.value.filter(row => row.status === 'ACTIVE'))
const points = computed(() => metadata.value.knowledgePoints.filter(row => row.name.toLowerCase().includes(search.value.trim().toLowerCase())))
function byOrder(a, b) { return a.sortOrder - b.sortOrder || a.id - b.id }
function children(parentId) { return metadata.value.chapters.filter(row => row.parentId === parentId).sort(byOrder) }
async function refresh() { metadata.value = (await teacherApi.bankMetadata(props.bankId, true)).data }
async function run(action, message) {
  error.value = ''; notice.value = ''; busy.value = true
  try { await action(); await refresh(); emit('changed'); notice.value = message }
  catch (e) { error.value = e.message || '操作失败' }
  finally { busy.value = false }
}
function editChapter(row) { Object.assign(chapterForm, { id: row.id, name: row.name, parentId: row.parentId ?? '', sortOrder: row.sortOrder }) }
function addChild(row) { Object.assign(chapterForm, { id: null, name: '', parentId: row.id, sortOrder: 0 }); tab.value = 'chapters' }
function resetChapter() { Object.assign(chapterForm, { id: null, name: '', parentId: '', sortOrder: 0 }) }
async function saveChapter() {
  const data = { name: chapterForm.name.trim(), parentId: chapterForm.parentId === '' ? null : Number(chapterForm.parentId), sortOrder: Number(chapterForm.sortOrder) }
  await run(() => teacherApi.saveBankChapter(props.bankId, chapterForm.id, data), '章节已保存')
  if (!error.value) resetChapter()
}
function chapterAction(row, action) {
  if (action === 'delete' && !window.confirm(`确定删除未使用章节“${row.name}”吗？`)) return
  const api = { archive: teacherApi.archiveBankChapter, restore: teacherApi.restoreBankChapter, delete: teacherApi.deleteBankChapter }[action]
  run(() => api(props.bankId, row.id), action === 'delete' ? '章节已删除' : '章节状态已更新')
}
function editPoint(row) { Object.assign(pointForm, { id: row.id, name: row.name, description: row.description || '' }) }
function resetPoint() { Object.assign(pointForm, { id: null, name: '', description: '' }) }
async function savePoint() {
  await run(() => teacherApi.saveBankKnowledgePoint(props.bankId, pointForm.id,
    { name: pointForm.name.trim(), description: pointForm.description.trim() || null }), '知识点已保存')
  if (!error.value) resetPoint()
}
function pointAction(row, action) {
  if (action === 'delete' && !window.confirm(`确定删除未使用知识点“${row.name}”吗？`)) return
  const api = { archive: teacherApi.archiveBankKnowledgePoint, restore: teacherApi.restoreBankKnowledgePoint, delete: teacherApi.deleteBankKnowledgePoint }[action]
  run(() => api(props.bankId, row.id), action === 'delete' ? '知识点已删除' : '知识点状态已更新')
}
function editTag(row) { Object.assign(tagForm, { id: row.id, name: row.name }) }
function resetTag() { Object.assign(tagForm, { id: null, name: '' }) }
async function saveTag() {
  await run(() => teacherApi.saveBankTag(props.bankId, tagForm.id, { name: tagForm.name.trim() }), '标签已保存')
  if (!error.value) resetTag()
}
function deleteTag(row) {
  if (!window.confirm(`删除标签“${row.name}”将解除题目关联，题目不会删除。确定继续吗？`)) return
  run(() => teacherApi.deleteBankTag(props.bankId, row.id), '标签已删除')
}
onMounted(() => refresh().catch(e => { error.value = e.message || '加载失败' }))
</script>

<template>
  <div class="bank-structure-manager">
    <div class="bank-structure-tabs" role="tablist" aria-label="分类管理">
      <button v-for="entry in [['chapters','章节'],['points','知识点'],['tags','标签']]" :key="entry[0]" type="button" role="tab"
        :aria-selected="tab === entry[0]" :class="{ active: tab === entry[0] }" @click="tab = entry[0]">{{ entry[1] }}</button>
    </div>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p><p v-if="notice" class="success-message" role="status">{{ notice }}</p>

    <section v-if="tab === 'chapters'" class="bank-structure-section">
      <form class="bank-structure-form" @submit.prevent="saveChapter">
        <label>章节名称<input v-model="chapterForm.name" maxlength="160" required /></label>
        <label>所属一级章节<select v-model="chapterForm.parentId"><option value="">一级章节</option><option v-for="root in rootOptions.filter(row => row.id !== chapterForm.id)" :key="root.id" :value="root.id">{{ root.name }}</option></select></label>
        <label>排序<input v-model.number="chapterForm.sortOrder" type="number" min="0" required /></label>
        <button class="primary" :disabled="busy">{{ chapterForm.id ? '保存修改' : '新增章节' }}</button>
        <button v-if="chapterForm.id" type="button" class="ghost" @click="resetChapter">取消</button>
      </form>
      <p class="muted">最多两级；归档一级章节会同时归档其直属子章节。</p>
      <div v-if="!roots.length" class="empty-state">暂无章节</div>
      <div v-for="root in roots" :key="root.id" class="bank-structure-root">
        <div class="bank-structure-row" :class="{ archived: root.status === 'ARCHIVED' }"><strong>{{ root.name }}</strong><small>排序 {{ root.sortOrder }}</small><span v-if="root.status === 'ARCHIVED'">已归档</span>
          <div class="actions"><button v-if="root.status === 'ACTIVE'" class="ghost" @click="addChild(root)">+ 子章节</button><button v-if="root.status === 'ACTIVE'" class="ghost" @click="editChapter(root)">编辑</button><button v-if="root.status === 'ACTIVE'" class="ghost" @click="chapterAction(root, 'archive')">归档</button><button v-else class="ghost" @click="chapterAction(root, 'restore')">恢复</button><button class="danger" @click="chapterAction(root, 'delete')">删除</button></div>
        </div>
        <div v-for="child in children(root.id)" :key="child.id" class="bank-structure-row child" :class="{ archived: child.status === 'ARCHIVED' }"><span>└ {{ child.name }}</span><small>排序 {{ child.sortOrder }}</small><span v-if="child.status === 'ARCHIVED'">已归档</span>
          <div class="actions"><button v-if="child.status === 'ACTIVE'" class="ghost" @click="editChapter(child)">编辑</button><button v-if="child.status === 'ACTIVE'" class="ghost" @click="chapterAction(child, 'archive')">归档</button><button v-else class="ghost" @click="chapterAction(child, 'restore')">恢复</button><button class="danger" @click="chapterAction(child, 'delete')">删除</button></div>
        </div>
      </div>
    </section>

    <section v-else-if="tab === 'points'" class="bank-structure-section">
      <form class="bank-structure-form" @submit.prevent="savePoint"><label>知识点名称<input v-model="pointForm.name" maxlength="160" required /></label><label>说明<input v-model="pointForm.description" maxlength="10000" /></label><button class="primary" :disabled="busy">{{ pointForm.id ? '保存修改' : '新增知识点' }}</button><button v-if="pointForm.id" type="button" class="ghost" @click="resetPoint">取消</button></form>
      <label class="bank-structure-search">搜索知识点<input v-model="search" placeholder="输入名称" /></label>
      <div v-if="!points.length" class="empty-state">暂无符合条件的知识点</div>
      <div v-for="point in points" :key="point.id" class="bank-structure-row" :class="{ archived: point.status === 'ARCHIVED' }"><div><strong>{{ point.name }}</strong><small v-if="point.description" class="muted">{{ point.description }}</small></div><span v-if="point.status === 'ARCHIVED'">已归档</span><div class="actions"><button v-if="point.status === 'ACTIVE'" class="ghost" @click="editPoint(point)">编辑</button><button v-if="point.status === 'ACTIVE'" class="ghost" @click="pointAction(point, 'archive')">归档</button><button v-else class="ghost" @click="pointAction(point, 'restore')">恢复</button><button class="danger" @click="pointAction(point, 'delete')">删除</button></div></div>
    </section>

    <section v-else class="bank-structure-section">
      <form class="bank-structure-form" @submit.prevent="saveTag"><label>标签名称<input v-model="tagForm.name" maxlength="160" required /></label><button class="primary" :disabled="busy">{{ tagForm.id ? '保存修改' : '新增标签' }}</button><button v-if="tagForm.id" type="button" class="ghost" @click="resetTag">取消</button></form>
      <p class="muted">删除标签只会解除题目关联，不会删除题目。</p>
      <div v-if="!metadata.tags.length" class="empty-state">暂无标签</div>
      <div v-for="tag in metadata.tags" :key="tag.id" class="bank-structure-row"><strong>{{ tag.name }}</strong><div class="actions"><button class="ghost" @click="editTag(tag)">重命名</button><button class="danger" @click="deleteTag(tag)">删除</button></div></div>
    </section>
  </div>
</template>
