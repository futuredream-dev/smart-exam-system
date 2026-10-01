export const lifecycleNames = { DRAFT:'草稿', NOT_STARTED:'待开始', IN_PROGRESS:'考试进行中', PENDING_REVIEW:'待阅卷', REVIEWING:'阅卷中', READY_TO_RELEASE:'待发布成绩', RESULTS_RELEASED:'成绩已发布' }
import { candidateStatus } from './statusDisplay'

export const candidateNames = Object.fromEntries(
  Object.entries(candidateStatus).map(([key, value]) => [key, value.label]),
)
