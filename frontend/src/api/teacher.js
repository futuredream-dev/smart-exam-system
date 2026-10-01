import http from './http'

export const teacherApi = {
  dashboard: () => http.get('/teacher/dashboard'),
  courses: () => http.get('/teacher/courses'),
  course: id => http.get('/teacher/courses/' + id),
  students: () => http.get('/teacher/students'),
  courseStudents: courseId => http.get('/teacher/courses/' + courseId + '/students'),
  addCourseStudent: (courseId, studentId) => http.post('/teacher/courses/' + courseId + '/students', { studentId }),
  removeCourseStudent: (courseId, studentId) => http.delete('/teacher/courses/' + courseId + '/students/' + studentId),
  resetStudentPassword: (courseId, studentId) => http.post('/teacher/courses/' + courseId + '/students/' + studentId + '/reset-password'),
  studentImportTemplate: courseId => http.get('/teacher/courses/' + courseId + '/students/import-template', { responseType: 'blob' }),
  previewStudentImport: (courseId, file, usernameColumn, nameColumn) => {
    const data = new FormData(); data.append('file', file)
    if (usernameColumn !== null && usernameColumn !== undefined) data.append('usernameColumn', usernameColumn)
    if (nameColumn !== null && nameColumn !== undefined) data.append('nameColumn', nameColumn)
    return http.post('/teacher/courses/' + courseId + '/students/import-preview', data, { headers: { 'Content-Type': 'multipart/form-data' }, timeout: 30000 })
  },
  importStudents: (courseId, file, usernameColumn, nameColumn) => {
    const data = new FormData(); data.append('file', file)
    if (usernameColumn !== null && usernameColumn !== undefined) data.append('usernameColumn', usernameColumn)
    if (nameColumn !== null && nameColumn !== undefined) data.append('nameColumn', nameColumn)
    return http.post('/teacher/courses/' + courseId + '/students/import', data, { headers: { 'Content-Type': 'multipart/form-data' }, timeout: 30000 })
  },
  saveCourse: (id, data) => id ? http.put('/teacher/courses/' + id, data) : http.post('/teacher/courses', data),
  deleteCourse: id => http.delete('/teacher/courses/' + id),
  exams: courseId => http.get('/teacher/courses/' + courseId + '/exams'),
  examCenter: params => http.get('/teacher/exams', { params }),
  exam: id => http.get('/teacher/exams/' + id),
  publishReadiness: id => http.get('/teacher/exams/' + id + '/publish-readiness'),
  examWorkspace: id => http.get('/teacher/exams/' + id + '/workspace'),
  examMonitor: id => http.get('/teacher/exams/' + id + '/monitor'),
  examMonitorEvents: (examId, studentId, page = 1, size = 10) => http.get('/teacher/exams/' + examId + '/monitor/students/' + studentId + '/events', { params: { page, size } }),
  examCandidates: id => http.get('/teacher/exams/' + id + '/candidates'),
  resultsAnalysis: id => http.get('/teacher/exams/' + id + '/results/analysis'),
  createMakeup: (id, data) => http.post('/teacher/exams/' + id + '/makeups', data),
  copyExam: (id, data) => http.post('/teacher/exams/' + id + '/copy', data),
  archiveExam: id => http.post('/teacher/exams/' + id + '/archive'),
  restoreExam: id => http.post('/teacher/exams/' + id + '/restore'),
  saveExam: (id, courseId, data) => id ? http.put('/teacher/exams/' + id, data) : http.post('/teacher/courses/' + courseId + '/exams', data),
  questions: id => http.get('/teacher/exams/' + id + '/questions'),
  saveQuestion: (id, examId, data) => id ? http.put('/teacher/questions/' + id, data) : http.post('/teacher/exams/' + examId + '/questions', data),
  bankCandidates: (examId, params) => http.get('/teacher/exams/' + examId + '/question-bank-items', { params }),
  addQuestionsFromBank: (examId, bankItemIds) => http.post('/teacher/exams/' + examId + '/questions/from-bank', { bankItemIds }),
  randomPreview: (examId, data) => http.post('/teacher/exams/' + examId + '/questions/random-preview', data),
  diagnoseRuleAssembly: (examId, data) => http.post('/teacher/exams/' + examId + '/rule-assembly/diagnose', data),
  previewRuleAssembly: (examId, data) => http.post('/teacher/exams/' + examId + '/rule-assembly/preview', data),
  confirmRuleAssembly: (examId, data) => http.post('/teacher/exams/' + examId + '/rule-assembly/confirm', data),
  interpretAiAssembly: (examId, requirement) => http.post('/teacher/exams/' + examId + '/ai-assembly/interpret', { requirement }, { timeout: 65000 }),
  diagnoseAiAssembly: (examId, data) => http.post('/teacher/exams/' + examId + '/ai-assembly/diagnose', data),
  recommendAiAssembly: (examId, data) => http.post('/teacher/exams/' + examId + '/ai-assembly/recommend', data, { timeout: 65000 }),
  deleteQuestion: id => http.delete('/teacher/questions/' + id),
  publish: id => http.post('/teacher/exams/' + id + '/publish'),
  gradingSubmissions: id => http.get('/teacher/grading/exams/' + id + '/submissions'),
  gradingTasks: params => http.get('/teacher/grading-tasks', { params }),
  gradingTaskSummary: params => http.get('/teacher/grading-tasks/summary', { params }),
  aiEvaluationSummary: examId => http.get(`/teacher/ai-evaluation/exams/${examId}/summary`),
  aiEvaluationQuestions: examId => http.get(`/teacher/ai-evaluation/exams/${examId}/questions`),
  aiEvaluationSamples: questionId => http.get(`/teacher/ai-evaluation/questions/${questionId}/samples`),
  exportExamResults: id => http.get('/teacher/grading/exams/' + id + '/results/export', { responseType: 'blob', rawResponse: true }),
  gradingPaper: id => http.get('/teacher/grading/submissions/' + id),
  aiGrade: id => http.post('/teacher/grading/answers/' + id + '/ai-grade'),
  reviewAnswer: (id, data) => http.put('/teacher/grading/answers/' + id + '/review', data),
  saveFinalComment: (id, teacherComment) => http.put('/teacher/grading/submissions/' + id + '/comment', { teacherComment }),
  publishScore: id => http.post('/teacher/grading/submissions/' + id + '/publish'),
  publishExamResults: id => http.post('/teacher/grading/exams/' + id + '/publish-results'),
  questionBanks: () => http.get('/teacher/question-banks'),
  availableBankCourses: () => http.get('/teacher/question-banks/available-courses'),
  questionBank: id => http.get('/teacher/question-banks/' + id),
  bankImportTemplate: bankId => http.get('/teacher/question-banks/' + bankId + '/imports/excel/template', { responseType: 'blob' }),
  previewBankImport: (bankId, file) => {
    const data = new FormData(); data.append('file', file)
    return http.post('/teacher/question-banks/' + bankId + '/imports/excel/preview', data, { headers: { 'Content-Type': 'multipart/form-data' }, timeout: 60000 })
  },
  confirmBankImport: (bankId, file, duplicatePolicy) => {
    const data = new FormData(); data.append('file', file)
    return http.post('/teacher/question-banks/' + bankId + '/imports/excel/confirm', data, { params: { duplicatePolicy }, headers: { 'Content-Type': 'multipart/form-data' }, timeout: 120000 })
  },
  saveQuestionBank: (id, data) => id ? http.put('/teacher/question-banks/' + id, data) : http.post('/teacher/question-banks', data),
  bindQuestionBankCourse: (id, courseId) => http.post('/teacher/question-banks/' + id + '/bind-course', { courseId }),
  archiveQuestionBank: id => http.delete('/teacher/question-banks/' + id),
  bankItems: (bankId, params) => http.get('/teacher/question-banks/' + bankId + '/items', { params, paramsSerializer: { indexes: null } }),
  bankItem: id => http.get('/teacher/question-bank-items/' + id),
  saveBankItem: (id, bankId, data) => id ? http.put('/teacher/question-bank-items/' + id, data) : http.post('/teacher/question-banks/' + bankId + '/items', data),
  readyBankItem: id => http.post('/teacher/question-bank-items/' + id + '/ready'),
  archiveBankItem: id => http.delete('/teacher/question-bank-items/' + id),
  bankMetadata: (bankId, includeArchived = false) => http.get('/teacher/question-banks/' + bankId + '/metadata', { params: { includeArchived } }),
  saveBankChapter: (bankId, id, data) => id
    ? http.put('/teacher/question-banks/' + bankId + '/chapters/' + id, data)
    : http.post('/teacher/question-banks/' + bankId + '/chapters', data),
  archiveBankChapter: (bankId, id) => http.post('/teacher/question-banks/' + bankId + '/chapters/' + id + '/archive'),
  restoreBankChapter: (bankId, id) => http.post('/teacher/question-banks/' + bankId + '/chapters/' + id + '/restore'),
  deleteBankChapter: (bankId, id) => http.delete('/teacher/question-banks/' + bankId + '/chapters/' + id),
  saveBankKnowledgePoint: (bankId, id, data) => id
    ? http.put('/teacher/question-banks/' + bankId + '/knowledge-points/' + id, data)
    : http.post('/teacher/question-banks/' + bankId + '/knowledge-points', data),
  archiveBankKnowledgePoint: (bankId, id) => http.post('/teacher/question-banks/' + bankId + '/knowledge-points/' + id + '/archive'),
  restoreBankKnowledgePoint: (bankId, id) => http.post('/teacher/question-banks/' + bankId + '/knowledge-points/' + id + '/restore'),
  deleteBankKnowledgePoint: (bankId, id) => http.delete('/teacher/question-banks/' + bankId + '/knowledge-points/' + id),
  saveBankTag: (bankId, id, data) => id
    ? http.put('/teacher/question-banks/' + bankId + '/tags/' + id, data)
    : http.post('/teacher/question-banks/' + bankId + '/tags', data),
  deleteBankTag: (bankId, id) => http.delete('/teacher/question-banks/' + bankId + '/tags/' + id),
  batchBankItemMetadata: (bankId, data) => http.patch('/teacher/question-banks/' + bankId + '/items/batch-metadata', data),
}
// 工作台聚合正式课程/考试接口，不引入文档外业务路径。
export async function loadWorkspace() {
  const courses = (await teacherApi.courses()).data
  const exams = (await Promise.all(courses.map(c => teacherApi.exams(c.id)))).flatMap(r => r.data)
  return { courses, exams }
}
export const statusNames = { DRAFT: '草稿', PUBLISHED: '已发布', GRADING: '阅卷中', RELEASED: '已发布成绩' }
export const formatTime = value => value ? value.replace('T', ' ').slice(0, 16) : '—'
