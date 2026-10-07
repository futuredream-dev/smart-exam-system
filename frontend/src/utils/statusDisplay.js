// Display metadata only. API enums and lifecycle decisions stay in their existing modules.
export const examBusinessStatus = {
  DRAFT: { label: '草稿', tone: 'neutral' },
  PUBLISHED: { label: '已发布考试', tone: 'info' },
  GRADING: { label: '阅卷中', tone: 'warning' },
  RELEASED: { label: '成绩已发布', tone: 'success' },
}

export const examDisplayStage = {
  DRAFT: { label: '草稿', tone: 'neutral' },
  SCHEDULED: { label: '待开始', tone: 'neutral' },
  RUNNING: { label: '进行中', tone: 'info' },
  ENDED: { label: '已结束', tone: 'neutral' },
  GRADING: { label: '阅卷中', tone: 'warning' },
  GRADED: { label: '待发布成绩', tone: 'warning' },
  RESULT_PARTIAL: { label: '成绩部分发布', tone: 'warning' },
  COMPLETED: { label: '已完成', tone: 'success' },
}

export const submissionStatus = {
  IN_PROGRESS: { label: '答题中', tone: 'info' },
  SUBMITTED: { label: '主动交卷', tone: 'neutral' },
  AUTO_SUBMITTED: { label: '自动交卷', tone: 'neutral' },
  AUTO_SUBMIT_PENDING: { label: '待自动交卷', tone: 'warning' },
  GRADED: { label: '已评分', tone: 'success' },
}

export const gradingStatus = {
  PENDING: { label: '待阅', tone: 'warning' },
  AUTO_GRADED: { label: '已自动评分', tone: 'success' },
  REVIEWED: { label: '已复核', tone: 'success' },
  PUBLISHED: { label: '成绩已发布', tone: 'success' },
}

export const candidateStatus = {
  NOT_ENTERED: { label: '未进入', tone: 'neutral' },
  IN_PROGRESS: { label: '答题中', tone: 'info' },
  PENDING_REVIEW: { label: '待阅', tone: 'warning' },
  AUTO_GRADED: { label: '已自动评分', tone: 'success' },
  REVIEWED: { label: '已复核', tone: 'success' },
  ABSENT: { label: '缺考', tone: 'neutral' },
}

export const studentExamStatus = {
  NOT_STARTED: { label: '待开始', tone: 'neutral' },
  AVAILABLE: { label: '可参加考试', tone: 'info' },
  MISSED_ENTRY: { label: '已错过入场', tone: 'danger' },
  IN_PROGRESS: { label: '答题中', tone: 'info' },
  PENDING_REVIEW: { label: '已交卷 · 待批阅', tone: 'warning' },
  PENDING_RELEASE: { label: '已交卷 · 待公布', tone: 'warning' },
  RESULTS_RELEASED: { label: '成绩已公布', tone: 'success' },
  ABSENT: { label: '已结束 · 未参加', tone: 'neutral' },
}

export const aiStatus = {
  SUCCESS: { label: '建议已生成', tone: 'success' },
  FAILED: { label: '生成失败', tone: 'danger' },
  NOT_CONFIGURED: { label: '未配置', tone: 'neutral' },
  NO_RESULT: { label: '未生成', tone: 'neutral' },
  MATCHED: { label: '已匹配', tone: 'success' },
  AMBIGUOUS: { label: '待选择', tone: 'warning' },
  UNRESOLVED: { label: '未精确匹配', tone: 'warning' },
}

export const releaseStatus = {
  PENDING_RELEASE: { label: '待发布', tone: 'warning' },
  RELEASED: { label: '成绩已发布', tone: 'success' },
  UNRELEASED: { label: '成绩未发布', tone: 'neutral' },
  INCONSISTENT: { label: '数据异常', tone: 'danger' },
}

export const accountStatus = {
  ACTIVE: { label: '启用', tone: 'success' },
  DISABLED: { label: '停用', tone: 'danger' },
}

export const bankStatus = {
  ACTIVE: { label: '可用', tone: 'success' },
  ARCHIVED: { label: '已归档', tone: 'neutral' },
}

export const questionStatus = {
  DRAFT: { label: '草稿', tone: 'neutral' },
  READY: { label: '可用', tone: 'success' },
  ARCHIVED: { label: '已归档', tone: 'neutral' },
}

export const examKind = {
  NORMAL: { label: '普通考试', tone: 'neutral' },
  MAKEUP: { label: '补考', tone: 'neutral' },
}

export const archiveStatus = {
  ARCHIVED: { label: '已归档', tone: 'neutral' },
  UNARCHIVED: { label: '未归档', tone: 'neutral' },
}

export const examBusinessStatusNames = Object.fromEntries(
  Object.entries(examBusinessStatus).map(([key, value]) => [key, value.label]),
)

export function statusLabel(group, status) {
  return group[status]?.label || status || '状态待确认'
}

export function statusTone(group, status) {
  return group[status]?.tone || 'neutral'
}
