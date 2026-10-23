import { safeReturnPath } from './returnPath.js'

export const teacherNavigation = [
  { path: '/teacher', label: '工作台', icon: '▦' },
  { path: '/teacher/exams', label: '考试管理', icon: '▣' },
  { path: '/teacher/question-banks', label: '题库管理', icon: '▥' },
  { path: '/teacher/courses', label: '课程管理', icon: '▤' },
]

export function teacherNavigationActive(path, target) {
  if (target === '/teacher') return path === target
  if (target === '/teacher/exams' && /^\/teacher\/(grading(?:-tasks|\/)|ai-evaluation(?:\/|$))/.test(path)) return true
  return path === target || path.startsWith(target + '/')
}

export function workspacePageKey(route) {
  return route.path.startsWith('/teacher/') ? route.path : route.fullPath
}

export function resolveExamTab(raw, stage) {
  if (raw === 'monitor') return { key: 'candidates', section: 'monitor' }
  if (['overview', 'paper', 'candidates', 'review', 'grading'].includes(raw)) return { key: raw, section: 'candidates' }
  if (stage === 'RUNNING') return { key: 'candidates', section: 'monitor' }
  const key = ({ DRAFT: 'paper', ENDED: 'review', GRADING: 'review', GRADED: 'grading', COMPLETED: 'grading', RESULT_PARTIAL: 'grading', NO_PARTICIPANTS: 'candidates' })[stage] || 'overview'
  return { key, section: 'candidates' }
}

export function teacherReturnPath(value) {
  const safe = safeReturnPath(value)
  if (!safe) return '/teacher/exams'
  const decoded = decodeURIComponent(safe)
  const path = new URL(decoded, 'https://internal.invalid').pathname
  // Reject normalization through dot segments as well as links into another role.
  if (!/^\/teacher(?:\/|$)/.test(path) || /(?:^|\/)\.{1,2}(?:\/|$)/.test(decoded.split(/[?#]/)[0])) return '/teacher/exams'
  return safe
}

function taskQuery(query) {
  return Object.fromEntries(['examId', 'taskStatus', 'aiStatus', 'keyword', 'page']
    .filter(key => typeof query[key] === 'string' && query[key] !== '')
    .map(key => [key, query[key]]))
}

export function gradingPaperRoute(examId, submissionId, query = {}) {
  const path = `/teacher/exams/${examId}/grading/submissions/${submissionId}`
  if (query.from === 'grading-tasks') return { path, query: { from: 'grading-tasks', ...taskQuery(query) } }
  if (query.returnTo) return { path, query: { returnTo: teacherReturnPath(query.returnTo) } }
  return path
}

export function gradingReturnTo(examId, query = {}) {
  if (query.from === 'grading-tasks') return { path: '/teacher/grading-tasks', query: taskQuery(query) }
  if (!examId) return '/teacher/exams'
  return { path: `/teacher/exams/${examId}`, query: { tab: 'review', ...(query.returnTo ? { returnTo: teacherReturnPath(query.returnTo) } : {}) } }
}

export function examDetailRoute(examId, returnTo, tab) {
  return { path: `/teacher/exams/${examId}`, query: { returnTo: teacherReturnPath(returnTo), ...(tab ? { tab } : {}) } }
}
