const lifecycle = {
  NOT_STARTED: { label: '待开始', action: '查看详情', tone: 'neutral' },
  AVAILABLE: { label: '可参加考试', action: '进入考试', tone: 'primary' },
  MISSED_ENTRY: { label: '已错过入场', action: null, tone: 'danger', hint: '已超过本场考试允许入场时间，当前无法进入考试。' },
  IN_PROGRESS: { label: '答题中', action: '继续答题', tone: 'primary' },
  PENDING_REVIEW: { label: '已交卷 · 待批阅', action: null, tone: 'warning', hint: '等待教师批阅' },
  PENDING_RELEASE: { label: '已交卷 · 待公布', action: null, tone: 'pending', hint: '成绩暂未公布' },
  RESULTS_RELEASED: { label: '成绩已公布', action: '查看成绩', tone: 'success' },
  ABSENT: { label: '已结束 · 未参加', action: null, tone: 'danger' },
}

export const lifecycleMeta = status => lifecycle[status] || {
  label: '状态待确认', action: null, tone: 'neutral', hint: '请稍后刷新查看',
}
