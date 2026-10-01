import http from './http'

export function listTeachers(keyword = '') { return http.get('/admin/teachers', { params: keyword ? { keyword } : {} }) }
export function createTeacher(data) { return http.post('/admin/teachers', data) }
export function setTeacherStatus(id, status) { return http.put(`/admin/teachers/${id}/status`, { status }) }
export function resetTeacherPassword(id) { return http.post(`/admin/teachers/${id}/reset-password`) }
export const adminApi = {
  dashboard: () => http.get('/admin/dashboard/summary'),
  students: params => http.get('/admin/students', { params }),
  setStudentStatus: (id, status) => http.put(`/admin/students/${id}/status`, { status }),
  resetStudentPassword: id => http.post(`/admin/students/${id}/reset-password`),
  courses: params => http.get('/admin/courses', { params }),
  exams: params => http.get('/admin/exams', { params }),
}
