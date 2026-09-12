import http from './http'

export function listTeachers(keyword = '') { return http.get('/admin/teachers', { params: keyword ? { keyword } : {} }) }
export function createTeacher(data) { return http.post('/admin/teachers', data) }
export function setTeacherStatus(id, status) { return http.put(`/admin/teachers/${id}/status`, { status }) }
export function resetTeacherPassword(id) { return http.post(`/admin/teachers/${id}/reset-password`) }
