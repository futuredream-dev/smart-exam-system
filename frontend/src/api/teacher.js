import http from './http'

export const teacherApi = {
  courses: () => http.get('/teacher/courses'),
  course: id => http.get('/teacher/courses/' + id),
  saveCourse: (id, data) => id ? http.put('/teacher/courses/' + id, data) : http.post('/teacher/courses', data),
  deleteCourse: id => http.delete('/teacher/courses/' + id),
  exams: courseId => http.get('/teacher/courses/' + courseId + '/exams'),
  exam: id => http.get('/teacher/exams/' + id),
  saveExam: (id, courseId, data) => id ? http.put('/teacher/exams/' + id, data) : http.post('/teacher/courses/' + courseId + '/exams', data),
  questions: id => http.get('/teacher/exams/' + id + '/questions'),
  saveQuestion: (id, examId, data) => id ? http.put('/teacher/questions/' + id, data) : http.post('/teacher/exams/' + examId + '/questions', data),
  deleteQuestion: id => http.delete('/teacher/questions/' + id),
  publish: id => http.post('/teacher/exams/' + id + '/publish'),
}
// 工作台聚合正式课程/考试接口，不引入文档外业务路径。
export async function loadWorkspace() {
  const courses = (await teacherApi.courses()).data
  const exams = (await Promise.all(courses.map(c => teacherApi.exams(c.id)))).flatMap(r => r.data)
  return { courses, exams }
}
export const statusNames = { DRAFT: '草稿', PUBLISHED: '已发布', GRADING: '阅卷中', RELEASED: '已发布成绩' }
export const formatTime = value => value ? value.replace('T', ' ').slice(0, 16) : '—'
