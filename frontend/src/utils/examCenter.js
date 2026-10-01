import { examDisplayStage } from './statusDisplay'

export const displayStageNames = Object.fromEntries(
  Object.entries(examDisplayStage).map(([key, value]) => [key, value.label]),
)

export const actionNames = {
  CONFIGURE_PAPER: '配置试卷', PUBLISH: '发布考试', VIEW: '查看考试',
  START_GRADING: '开始阅卷', CONTINUE_GRADING: '继续阅卷',
  RELEASE_RESULTS: '发布成绩', VIEW_RESULTS: '查看成绩',
  CONTINUE_RELEASE: '查看成绩',
}

export const stageTabs = [
  { key: '', label: '全部', stages: [] },
  { key: 'draft', label: '草稿', stages: ['DRAFT'] },
  { key: 'scheduled', label: '待开始', stages: ['SCHEDULED'] },
  { key: 'running', label: '进行中', stages: ['RUNNING'] },
  { key: 'pending', label: '待处理', stage: 'TODO' },
  { key: 'completed', label: '已完成', stages: ['COMPLETED'] },
  { key: 'archived', label: '已归档', stages: [] },
]

export function actionRoute(examId, action) {
  const base = `/teacher/exams/${examId}`
  if (action === 'CONFIGURE_PAPER') return `${base}?tab=paper`
  if (action === 'START_GRADING' || action === 'CONTINUE_GRADING') return `/teacher/grading-tasks?examId=${examId}`
  if (action === 'RELEASE_RESULTS' || action === 'VIEW_RESULTS' || action === 'CONTINUE_RELEASE') return `${base}?tab=grading`
  return base
}
