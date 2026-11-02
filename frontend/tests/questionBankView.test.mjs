import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { parse, compileScript } from 'vue/compiler-sfc'
import * as vue from 'vue'
import * as metadata from '../src/utils/questionBankMetadata.js'
import * as selection from '../src/utils/questionBankSelection.js'

async function setupView(file, imports, props = {}) {
  const source = await readFile(new URL(`../src/${file}`, import.meta.url), 'utf8')
  const compiled = compileScript(parse(source).descriptor, { id: 'bank', genDefaultAs: 'component' })
  const code = compiled.content
    .replace(/import\s*{([^}]+)}\s*from\s*['"]([^'"]+)['"];?/g, (_, bindings, from) => `const {${bindings.replace(/\bas\b/g, ':')}} = imports[${JSON.stringify(from)}] || {};`)
    .replace(/import\s+(\w+)\s+from\s*['"][^'"]+['"];?/g, 'const $1 = {};')
  const events = []
  const component = new Function('imports', 'window', `${code}; return component`)(imports, { confirm: () => true })
  return { state: component.setup(props, { expose() {}, emit: (...args) => events.push(args) }), events }
}

async function bankPage(total = 300) {
  const route = vue.reactive({ path: '/teacher/question-banks/7', fullPath: '/teacher/question-banks/7?page=2', params: { bankId: '7' }, query: { page: '2' } })
  const calls = [], pushes = [], guards = {}
  const rows = [{ id: 21, title: '摘要', status: 'DRAFT' }]
  const api = {
    questionBank: async () => ({ data: { id: 7, scope: 'TEACHER', status: 'ACTIVE' } }),
    bankMetadata: async () => ({ data: { chapters: [], knowledgePoints: [], tags: [] } }),
    bankItems: async () => ({ data: rows }),
    bankItemsPage: async (id, params) => { calls.push(['page', id, params]); return { data: { items: total ? rows : [], total, page: params.page, size: params.size } } },
    bankItem: async id => { calls.push(['detail', id]); return { data: { ...rows[0], content: '按需全文', options: [], scoringPoints: [] } } },
    archiveBankItem: async () => ({}),
  }
  const page = await setupView('views/teacher/QuestionBankDetailView.vue', { vue: { ...vue, onMounted() {}, onUnmounted() {} }, 'vue-router': { useRoute: () => route, useRouter: () => ({ push: value => pushes.push(value), replace: value => pushes.push(value) }), onBeforeRouteLeave: callback => { guards.leave = callback }, onBeforeRouteUpdate: callback => { guards.update = callback } }, '../../api/teacher': { teacherApi: api }, '../../utils/questionBankMetadata': metadata, '../../utils/questionBankSelection': selection })
  return { ...page, route, calls, pushes, guards }
}

test('bank requests one bounded summary page without clearing off-page selections', async () => {
  const { state: s, calls } = await bankPage(); s.selectedIds.value = [3]
  await s.load()
  assert.equal(calls.filter(call => call[0] === 'page').length, 1)
  assert.equal(calls[0][2].page, 2); assert.equal(calls[0][2].size, 20); assert.equal(calls[0][2].excludeArchived, true)
  assert.deepEqual(s.selectedIds.value, [3]); assert.equal(calls.some(call => call[0] === 'detail'), false)
})
test('zero-result bank pages normalize an obsolete page while preserving applied filters', async () => {
  const { state: s, route, pushes } = await bankPage(0)
  route.query = { page: '8', keyword: '消失的题目', size: '50' }
  await s.loadRows()
  assert.equal(s.pageCount.value, 1); assert.deepEqual(s.rows.value, [])
  assert.equal(pushes.length, 1); assert.equal(pushes[0].query.page, undefined)
  assert.equal(pushes[0].query.keyword, '消失的题目'); assert.equal(pushes[0].query.size, 50)
})
test('editing a summary first loads its full item and filters reset only the page', async () => {
  const { state: s, calls, pushes } = await bankPage(); await s.load()
  await s.edit(s.rows.value[0]); assert.equal(s.selected.value.content, '按需全文'); assert.equal(calls.filter(call => call[0] === 'detail').length, 1)
  s.filters.keyword = 'Java'; s.search(); assert.equal(pushes[0].query.keyword, 'Java'); assert.equal(pushes[0].query.page, undefined)
})
test('item form reports dirty and busy, and a failed save keeps edited input', async () => {
  const { state: s, events } = await setupView('components/QuestionBankItemForm.vue', { vue, '../api/teacher': { teacherApi: { saveBankItem: async () => { throw new Error('保存失败') } } }, '../utils/questionBankMetadata': metadata }, { bankId: 7, metadata: { chapters: [], knowledgePoints: [], tags: [] }, allMetadata: {} })
  s.form.title = '保留输入'; await vue.nextTick()
  assert.equal(s.dirty?.value, true); assert.equal(events.some(([name, value]) => name === 'dirty-change' && value === true), true)
  await s.save(); assert.equal(s.form.title, '保留输入'); assert.equal(s.dirty.value, true)
  assert.equal(events.some(([name, value]) => name === 'busy-change' && value === true), true)
  assert.equal(events.filter(([name]) => name === 'busy-change').at(-1)[1], false)
})

test('archiving a selected item removes that known invalid choice while retaining off-page choices', async () => {
  const { state: s } = await bankPage(); await s.load(); s.selectedIds.value = [21, 22]
  await s.archive(s.rows.value[0]); assert.deepEqual(s.selectedIds.value, [22])
})

test('a rejected native checkbox change restores its rendered state at the selection limit', async () => {
  const { state: s } = await bankPage()
  s.selectedIds.value = Array.from({ length: 200 }, (_, index) => index + 1)
  const single = { target: { checked: true } }, page = { target: { checked: true } }
  s.setSelection([{ id: 201, status: 'DRAFT' }], true, single)
  assert.equal(single.target.checked, false)
  s.setSelection([{ id: 201, status: 'DRAFT' }, { id: 202, status: 'READY' }], true, page)
  assert.equal(page.target.checked, false); assert.equal(s.selectedIds.value.length, 200)
})

test('leaving an edited item uses the same discard confirmation and keeps input when cancelled', async () => {
  const { state: s, guards, route, pushes } = await bankPage(); let requests = 0
  assert.equal(typeof guards.leave, 'function'); assert.equal(typeof guards.update, 'function')
  s.editorModal.value = { requestClose: () => { requests++ } }; s.opened.value = true; s.formDirty.value = true
  const target = { path: '/teacher/exams', fullPath: '/teacher/exams?page=3' }
  assert.equal(guards.leave(target, route), false); assert.equal(requests, 1); assert.equal(s.opened.value, true)
  s.cancelEditorNavigation(); assert.equal(s.pendingEditorPath.value, ''); assert.equal(s.opened.value, true)
  guards.leave(target, route); s.closeEditor(); assert.equal(pushes.at(-1), '/teacher/exams?page=3')
})

test('saving blocks route exit and refresh, while a clean closed editor leaves normally', async () => {
  const { state: s, guards, route } = await bankPage()
  assert.equal(typeof guards.leave, 'function')
  s.opened.value = true; s.formBusy.value = true
  assert.equal(guards.leave({ path: '/teacher/exams', fullPath: '/teacher/exams' }, route), false)
  const event = { prevented: false, preventDefault() { this.prevented = true } }
  s.beforeEditorUnload(event); assert.equal(event.prevented, true); assert.equal(event.returnValue, '')
  s.opened.value = false; assert.equal(guards.leave({ path: '/teacher/exams', fullPath: '/teacher/exams' }, route), true)
})

test('Vue rendered native checkbox agrees with the model after rejecting a 201st choice', async () => {
  for (const pageSelection of [false, true]) {
    const { state: s } = await bankPage()
    s.selectedIds.value = Array.from({ length: 200 }, (_, index) => index + 1)
    s.rows.value = [{ id: 201, status: 'DRAFT' }, { id: 202, status: 'READY' }]
    const root = { children: [] }
    const renderer = vue.createRenderer({
      createElement: tag => ({ tag, props: {}, children: [] }), insert: (node, parent) => parent.children.push(node),
      patchProp: (node, key, oldValue, value) => { node.props[key] = value }, remove() {},
      createText: text => ({ text }), createComment: text => ({ text }), setText() {}, setElementText() {}, parentNode() {}, nextSibling() {},
    })
    const app = renderer.createApp({ setup: () => () => vue.h('input', { type: 'checkbox', checked: pageSelection ? s.allVisibleSelected.value : s.selectedIds.value.includes(201),
      onChange: event => pageSelection ? s.toggleVisible(event) : s.setSelection([s.rows.value[0]], event.target.checked, event) }) })
    app.mount(root)
    const input = root.children[0]; input.props.checked = true // native click changes checked before dispatch
    input.props.onChange({ target: input.props }); await vue.nextTick()
    assert.equal(input.props.checked, false); assert.equal(s.selectedIds.value.includes(201), false)
    app.unmount()
  }
})
