import { studentExamStatus } from './statusDisplay'

const actions = {
  NOT_STARTED: { action: '查看详情' },
  AVAILABLE: { action: '进入考试' },
  MISSED_ENTRY: { action: null, hint: '已超过本场考试允许入场时间，当前无法进入考试。' },
  IN_PROGRESS: { action: '继续考试' },
  PENDING_REVIEW: { action: null, hint: '答卷已提交，等待教师批阅。' },
  PENDING_RELEASE: { action: null, hint: '答卷已提交，等待成绩公布。' },
  RESULTS_RELEASED: { action: '查看成绩' },
  ABSENT: { action: null, hint: '考试已结束，本场考试未参加。' },
}

export const lifecycleMeta = status => studentExamStatus[status]
  ? { ...studentExamStatus[status], ...actions[status] }
  : { label: '状态待确认', action: null, tone: 'neutral', hint: '请稍后刷新查看' }
