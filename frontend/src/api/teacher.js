import http from './http'

export const teacherApi = {
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
  exam: id => http.get('/teacher/exams/' + id),
  saveExam: (id, courseId, data) => id ? http.put('/teacher/exams/' + id, data) : http.post('/teacher/courses/' + courseId + '/exams', data),
  questions: id => http.get('/teacher/exams/' + id + '/questions'),
  saveQuestion: (id, examId, data) => id ? http.put('/teacher/questions/' + id, data) : http.post('/teacher/exams/' + examId + '/questions', data),
  bankCandidates: (examId, params) => http.get('/teacher/exams/' + examId + '/question-bank-items', { params }),
  addQuestionsFromBank: (examId, bankItemIds) => http.post('/teacher/exams/' + examId + '/questions/from-bank', { bankItemIds }),
  randomPreview: (examId, data) => http.post('/teacher/exams/' + examId + '/questions/random-preview', data),
  deleteQuestion: id => http.delete('/teacher/questions/' + id),
  publish: id => http.post('/teacher/exams/' + id + '/publish'),
  gradingExams: () => http.get('/teacher/grading/exams'),
  gradingSubmissions: id => http.get('/teacher/grading/exams/' + id + '/submissions'),
  gradingPaper: id => http.get('/teacher/grading/submissions/' + id),
  aiGrade: id => http.post('/teacher/grading/answers/' + id + '/ai-grade'),
  reviewAnswer: (id, data) => http.put('/teacher/grading/answers/' + id + '/review', data),
  saveFinalComment: (id, teacherComment) => http.put('/teacher/grading/submissions/' + id + '/comment', { teacherComment }),
  publishScore: id => http.post('/teacher/grading/submissions/' + id + '/publish'),
  questionBanks: () => http.get('/teacher/question-banks'),
  questionBank: id => http.get('/teacher/question-banks/' + id),
  saveQuestionBank: (id, data) => id ? http.put('/teacher/question-banks/' + id, data) : http.post('/teacher/question-banks', data),
  archiveQuestionBank: id => http.delete('/teacher/question-banks/' + id),
  bankItems: (bankId, params) => http.get('/teacher/question-banks/' + bankId + '/items', { params }),
  bankItem: id => http.get('/teacher/question-bank-items/' + id),
  saveBankItem: (id, bankId, data) => id ? http.put('/teacher/question-bank-items/' + id, data) : http.post('/teacher/question-banks/' + bankId + '/items', data),
  readyBankItem: id => http.post('/teacher/question-bank-items/' + id + '/ready'),
  archiveBankItem: id => http.delete('/teacher/question-bank-items/' + id),
}
// 工作台聚合正式课程/考试接口，不引入文档外业务路径。
export async function loadWorkspace() {
  const courses = (await teacherApi.courses()).data
  const exams = (await Promise.all(courses.map(c => teacherApi.exams(c.id)))).flatMap(r => r.data)
  return { courses, exams }
}
export const statusNames = { DRAFT: '草稿', PUBLISHED: '已发布', GRADING: '阅卷中', RELEASED: '已发布成绩' }
export const formatTime = value => value ? value.replace('T', ' ').slice(0, 16) : '—'
