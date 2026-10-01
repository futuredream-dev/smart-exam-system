export const studentStatusNames = {
  NOT_ENTERED: '未进入',
  IN_PROGRESS: '考试中',
  AUTO_SUBMIT_PENDING: '待自动提交',
  SUBMITTED: '已交卷',
  AUTO_SUBMITTED: '自动提交',
  GRADED: '已阅卷',
}

export const eventTypeNames = {
  TAB_HIDDEN: '切换或隐藏考试页面',
  PAGE_LEAVE: '离开考试页面',
  RE_ENTER: '重新进入考试',
}

const submittedStatuses = new Set(['SUBMITTED', 'AUTO_SUBMITTED', 'GRADED', 'AUTO_SUBMIT_PENDING'])

export function filterMonitorStudents(students, keyword, status) {
  const search = String(keyword || '').trim().toLocaleLowerCase()
  return (students || []).filter(student => {
    const matchesSearch = !search || [student.studentName, student.studentNo]
      .some(value => String(value || '').toLocaleLowerCase().includes(search))
    const matchesStatus = status === 'NOT_ENTERED' ? student.displayStatus === 'NOT_ENTERED'
      : status === 'IN_PROGRESS' ? student.displayStatus === 'IN_PROGRESS'
        : status === 'SUBMITTED' ? submittedStatuses.has(student.displayStatus)
          : status === 'HAS_EVENTS' ? Number(student.eventCount) > 0 : true
    return matchesSearch && matchesStatus
  })
}

// ExamCenterService currently returns RUNNING for an exam in progress.
export function monitorIsRunning(runtimePhase) {
  return runtimePhase === 'RUNNING' || runtimePhase === 'IN_PROGRESS'
}
