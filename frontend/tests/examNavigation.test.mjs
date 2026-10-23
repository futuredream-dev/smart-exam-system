import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { parse, compileScript } from 'vue/compiler-sfc'
import * as vue from 'vue'
import * as vueRouter from 'vue-router'
import { renderToString } from 'vue/server-renderer'

const navigation = await import('../src/utils/examNavigation.js').catch(() => ({}))
function capability(name, ...args) {
  assert.equal(typeof navigation[name], 'function', `${name} is not implemented`)
  return navigation[name](...args)
}

async function renderLayout(role, path, mustChangePassword = false) {
  const source = await readFile(new URL('../src/layouts/AppLayout.vue', import.meta.url), 'utf8')
  const compiled = compileScript(parse(source).descriptor, { id: 'navigation', inlineTemplate: true })
  const imports = { vue, 'vue-router': vueRouter, '../auth/auth': { authState: vue.reactive({ currentUser: { role, name: '测试用户', username: 'test', mustChangePassword } }), clearSession() {} }, '../utils/examNavigation': navigation }
  const code = compiled.content
    .replace(/import\s*{([^}]+)}\s*from\s*['"]([^'"]+)['"];?/g, (_, bindings, from) => `const {${bindings.replace(/\bas\b/g, ':')}} = imports[${JSON.stringify(from)}];`)
    .replace(/^import\s*['"][^'"]+['"];?\s*$/gm, '')
    .replace('export default', 'const component =')
  const component = new Function('imports', `${code}; return component`)(imports)
  const router = vueRouter.createRouter({ history: vueRouter.createMemoryHistory(), routes: [{ path: '/:all(.*)*', component: { render: () => null } }] })
  await router.push(path)
  const app = vue.createSSRApp(component, { portalName: role }); app.use(router)
  return renderToString(app)
}

test('rendered teacher navigation exposes four task areas and marks grading as exam management', async () => {
  const html = await renderLayout('TEACHER', '/teacher/grading-tasks?taskStatus=PENDING_REVIEW&page=2')
  const nav = html.match(/<nav[^>]*aria-label="教师导航"[^>]*>([\s\S]*?)<\/nav>/)?.[1] || ''
  assert.deepEqual([...nav.matchAll(/href="([^"]+)"/g)].map(match => match[1]), ['/teacher', '/teacher/exams', '/teacher/question-banks', '/teacher/courses'])
  assert.match(nav, /考试管理/); assert.match(nav, /题库管理/); assert.match(nav, /课程管理/)
  assert.match(nav, /<a[^>]*(?:class="[^"]*active[^"]*"[^>]*href="\/teacher\/exams"|href="\/teacher\/exams"[^>]*class="[^"]*active)/)
})

test('rendered admin and student navigation stays role scoped, including mandatory password change', async () => {
  for (const [role, path, expected] of [['ADMIN', '/admin', ['/admin', '/admin/teachers', '/admin/students', '/admin/courses', '/admin/exams']], ['STUDENT', '/student', ['/student', '/student/results', '/student/change-password']]]) {
    const html=await renderLayout(role,path)
    const nav=html.match(/<nav[^>]*>([\s\S]*?)<\/nav>/)?.[1] || ''
    assert.deepEqual([...nav.matchAll(/href="([^"]+)"/g)].map(match=>match[1]),expected)
    assert.doesNotMatch(nav, /href="\/teacher/)
  }
  const locked = await renderLayout('TEACHER', '/teacher/change-password', true)
  const nav = locked.match(/<nav[^>]*aria-label="教师导航"[^>]*>([\s\S]*?)<\/nav>/)?.[1] || ''
  assert.doesNotMatch(nav, /href=/)
})

test('legacy tab URLs retain their original content while monitor is grouped under exam affairs', () => {
  for (const [raw,key,section] of [['overview','overview','candidates'],['paper','paper','candidates'],['candidates','candidates','candidates'],['grading','grading','candidates'],['monitor','candidates','monitor'],['review','review','candidates']]) {
    assert.deepEqual(capability('resolveExamTab',raw,'RUNNING'),{key,section})
  }
  assert.deepEqual(capability('resolveExamTab',undefined,'DRAFT'),{key:'paper',section:'candidates'})
  assert.deepEqual(capability('resolveExamTab',undefined,'RUNNING'),{key:'candidates',section:'monitor'})
  assert.equal(capability('resolveExamTab',undefined,'GRADING').key,'review')
  assert.equal(capability('resolveExamTab',undefined,'COMPLETED').key,'grading')
})

test('teacher query changes preserve a view, but changing an exam or paper always creates a new view', () => {
  const key=(path,query)=>capability('workspacePageKey',{path,fullPath:path+query})
  assert.equal(key('/teacher/exams/7','?tab=paper'),key('/teacher/exams/7','?tab=grading'))
  assert.notEqual(key('/teacher/exams/7',''),key('/teacher/exams/8',''))
  assert.notEqual(key('/teacher/exams/7/grading/submissions/10',''),key('/teacher/exams/7/grading/submissions/11',''))
  assert.notEqual(key('/student/paper/10','?q=1'),key('/student/paper/10','?q=2'))
})

test('return paths preserve exam filters and reject external or different role targets', () => {
  const path='/teacher/exams?tab=pending&courseId=4&keyword=Java&page=3'
  assert.equal(capability('teacherReturnPath',path),path)
  for (const value of ['https://example.test','//example.test','/student/paper/10','/admin','/login','/teacher/../student']) assert.equal(capability('teacherReturnPath',value),'/teacher/exams')
})

test('next-paper navigation carries the original task filters and returns to the same task page', () => {
  const query={from:'grading-tasks',examId:'7',taskStatus:'PENDING_REVIEW',aiStatus:'FAILED',keyword:'张同学',page:'3'}
  assert.deepEqual(capability('gradingPaperRoute',7,11,query),{path:'/teacher/exams/7/grading/submissions/11',query})
  const {from,...filters}=query
  assert.deepEqual(capability('gradingReturnTo',7,query),{path:'/teacher/grading-tasks',query:filters})
})

async function detailPage(query = {}, api = {}) {
  const source = await readFile(new URL('../src/views/teacher/ExamDetailView.vue', import.meta.url), 'utf8')
  const compiled = compileScript(parse(source).descriptor, { id: 'detail', genDefaultAs: 'component' })
  const route = vue.reactive({ path: '/teacher/exams/7', params: { examId: '7' }, query })
  const pushes = []
  const imports = { vue: { ...vue, onMounted() {} }, 'vue-router': { useRoute: () => route, useRouter: () => ({ push: value => pushes.push(value) }) }, '../../api/teacher': { teacherApi: api }, '../../utils/examCenter': {}, '../../utils/examNavigation': navigation }
  const code = compiled.content
    .replace(/import\s*{([^}]+)}\s*from\s*['"]([^'"]+)['"];?/g, (_, bindings, from) => `const {${bindings.replace(/\bas\b/g, ':')}} = imports[${JSON.stringify(from)}] || {};`)
    .replace(/import\s+(\w+)\s+from\s*['"][^'"]+['"];?/g, 'const $1 = {};')
  const component = new Function('imports', `${code}; return component`)(imports)
  const state = component.setup({}, { expose() {} })
  state.exam.value = { id: 7, examId: 7, displayStage: 'GRADING', nextAction: 'CONTINUE_GRADING', status: 'PUBLISHED' }
  return { state, route, pushes }
}

test('detail uses five task tabs and applies legacy monitor and results URLs', async () => {
  const { state: s, route } = await detailPage({ tab: 'monitor' })
  assert.deepEqual(s.tabs.value, [['overview', '概览'], ['paper', '试卷'], ['candidates', '考生与考务'], ['review', '阅卷'], ['grading', '成绩']])
  assert.equal(s.active.value, 'candidates')
  assert.equal(s.affairsSection.value, 'monitor')
  route.query.tab = 'grading'; assert.equal(s.active.value, 'grading')
})

test('detail tab changes retain exam list context and grading stays within this exam', async () => {
  const { state: s, pushes } = await detailPage({ returnTo: '/teacher/exams?tab=pending&page=3' })
  s.tab('paper')
  assert.deepEqual(pushes[0], { path: '/teacher/exams/7', query: { returnTo: '/teacher/exams?tab=pending&page=3', tab: 'paper' } })
  s.primary()
  assert.equal(pushes[1].query.tab, 'review')
  assert.equal(pushes[1].path, '/teacher/exams/7')
})

async function renderGradingPanel(mode) {
  const source = await readFile(new URL('../src/components/ExamGradingPanel.vue', import.meta.url), 'utf8')
  const compiled = compileScript(parse(source).descriptor, { id: 'grading-panel', inlineTemplate: true })
  const imports = { vue, '../api/teacher': {}, '../utils/statusDisplay': {}, '../utils/examNavigation': navigation }
  const code = compiled.content
    .replace(/import\s*{([^}]+)}\s*from\s*['"]([^'"]+)['"];?/g, (_, bindings, from) => `const {${bindings.replace(/\bas\b/g, ':')}} = imports[${JSON.stringify(from)}];`)
    .replace(/import\s+(\w+)\s+from\s*['"][^'"]+['"];?/g, 'const $1 = { render: () => null };')
    .replace('export default', 'const component =')
  const component = new Function('imports', `${code}; return component`)(imports)
  return renderToString(vue.createSSRApp(component, { examId: 7, mode, workspace: { canReleaseResults: true, displayStage: 'GRADED' } }))
}

test('review and results panels expose only actions relevant to the chosen task', async () => {
  const review = await renderGradingPanel('review'), results = await renderGradingPanel('results')
  assert.doesNotMatch(review, /发布成绩|导出成绩/)
  assert.match(review, /已提交答卷/)
  assert.match(results, /发布成绩/); assert.match(results, /导出成绩/)
  assert.doesNotMatch(results, /正在加载已提交答卷/)
})

test('AI analysis opens the requested owned exam and keeps a safe return path', async () => {
  const source = await readFile(new URL('../src/views/teacher/AiEvaluationView.vue', import.meta.url), 'utf8')
  const compiled = compileScript(parse(source).descriptor, { id: 'analysis', genDefaultAs: 'component' })
  let mounted
  const imports = { vue: { ...vue, onMounted: callback => { mounted = callback }, watch() {} }, 'vue-router': { useRoute: () => ({ query: { examId: '8', returnTo: '/teacher/exams/8?tab=grading' } }), useRouter: () => ({ replace() {} }) }, '../../api/teacher': { loadWorkspace: async () => ({ exams: [{ id: 7, title: '甲' }, { id: 8, title: '乙' }] }) }, '../../utils/examNavigation': navigation }
  const code = compiled.content.replace(/import\s*{([^}]+)}\s*from\s*['"]([^'"]+)['"];?/g, (_, bindings, from) => `const {${bindings.replace(/\bas\b/g, ':')}} = imports[${JSON.stringify(from)}];`)
  const component = new Function('imports', `${code}; return component`)(imports)
  const state = component.setup({}, { expose() {} }); await mounted()
  assert.equal(state.examId.value, '8')
  assert.equal(state.returnTo.value, '/teacher/exams/8?tab=grading')
})

test('home caps upcoming exams at five and pending papers lead to the review task', async () => {
  const source = await readFile(new URL('../src/views/teacher/TeacherHomeView.vue', import.meta.url), 'utf8')
  const compiled = compileScript(parse(source).descriptor, { id: 'home', genDefaultAs: 'component' })
  const imports = { vue: { ...vue, onMounted() {} }, '../../api/teacher': {}, '../../auth/auth': {} }
  const code = compiled.content.replace(/import\s*{([^}]+)}\s*from\s*['"]([^'"]+)['"];?/g, (_, bindings, from) => `const {${bindings.replace(/\bas\b/g, ':')}} = imports[${JSON.stringify(from)}];`)
  const state = new Function('imports', `${code}; return component`)(imports).setup({}, { expose() {} })
  state.dashboard.value.upcoming = Array.from({ length: 8 }, (_, id) => ({ examId: id + 1 }))
  assert.equal(state.visibleUpcoming?.value.length, 5)
  assert.equal(state.reviewLink(7), '/teacher/exams/7?tab=review')
})

for (const [displayStage, canReleaseResults] of [['GRADED', true], ['NO_PARTICIPANTS', false], ['GRADING', false]]) test(`switching tasks refreshes running exam to ${displayStage}`, async () => {
  let reads = 0
  const { state: s, route } = await detailPage({ tab: 'monitor' }, {
    exam: async () => { reads++; return { data: { id: 7, examId: 7, displayStage, nextAction: canReleaseResults ? 'RELEASE_RESULTS' : 'VIEW' } } },
    examWorkspace: async () => ({ data: { canReleaseResults, displayStage } }),
    questions: async () => ({ data: [] }),
  })
  s.exam.value.displayStage = 'RUNNING'; s.workspace.value = { canReleaseResults: false }
  route.query.tab = 'grading'
  await vue.nextTick(); await vue.nextTick(); await vue.nextTick()
  assert.equal(reads, 1)
  assert.equal(s.stage.value, displayStage)
  assert.equal(s.workspace.value.canReleaseResults, canReleaseResults)
})

test('a late detail refresh cannot overwrite the newer stage after rapid tab changes', async () => {
  const pending = []
  const { state: s, route } = await detailPage({ tab: 'overview' }, {
    exam: () => new Promise(resolve => pending.push(resolve)),
    examWorkspace: async () => ({ data: { canReleaseResults: true } }),
    questions: async () => ({ data: [] }),
  })
  route.query.tab = 'review'; await vue.nextTick()
  route.query.tab = 'grading'; await vue.nextTick()
  assert.equal(pending.length, 2)
  pending[1]({ data: { displayStage: 'GRADED' } }); await vue.nextTick(); await vue.nextTick()
  pending[0]({ data: { displayStage: 'RUNNING' } }); await vue.nextTick(); await vue.nextTick()
  assert.equal(s.stage.value, 'GRADED')
})
