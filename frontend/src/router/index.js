import { createRouter, createWebHistory } from 'vue-router'
import AppLayout from '../layouts/AppLayout.vue'
import LoginView from '../views/LoginView.vue'
import StudentHomeView from '../views/student/StudentHomeView.vue'
import TeacherHomeView from '../views/teacher/TeacherHomeView.vue'
import CoursesView from '../views/teacher/CoursesView.vue'
import CourseDetailView from '../views/teacher/CourseDetailView.vue'
import ExamsView from '../views/teacher/ExamsView.vue'
import ExamDetailView from '../views/teacher/ExamDetailView.vue'
import GradingExamsView from '../views/teacher/GradingExamsView.vue'
import GradingSubmissionsView from '../views/teacher/GradingSubmissionsView.vue'
import GradingPaperView from '../views/teacher/GradingPaperView.vue'
import StudentExamDetailView from '../views/student/StudentExamDetailView.vue'
import StudentPaperView from '../views/student/StudentPaperView.vue'
import StudentResultsView from '../views/student/StudentResultsView.vue'
import { authState, restoreSession } from '../auth/auth'

const routes = [
  {
    path: '/',
    redirect: '/login',
  },
  {
    path: '/login',
    name: 'login',
    component: LoginView,
    meta: { title: '登录' },
  },
  {
    path: '/teacher',
    component: AppLayout,
    props: { portalName: '教师端' },
    meta: { requiresAuth: true, role: 'TEACHER' },
    children: [
      {
        path: '',
        name: 'teacher-home',
        component: TeacherHomeView,
        meta: { title: '教师工作台' },
      },
      { path: 'courses', component: CoursesView, meta: { title: '课程管理' } },
      { path: 'courses/:courseId', component: CourseDetailView, meta: { title: '课程详情' } },
      { path: 'exams', component: ExamsView, meta: { title: '考试管理' } },
      { path: 'exams/:examId', component: ExamDetailView, meta: { title: '考试详情' } },
      { path: 'grading', component: GradingExamsView, meta: { title: '考试阅卷' } },
      { path: 'grading/exams/:examId', component: GradingSubmissionsView, meta: { title: '答卷列表' } },
      { path: 'grading/submissions/:submissionId', component: GradingPaperView, meta: { title: '学生答卷' } },
    ],
  },
  {
    path: '/student',
    component: AppLayout,
    props: { portalName: '学生端' },
    meta: { requiresAuth: true, role: 'STUDENT' },
    children: [
      {
        path: '',
        name: 'student-home',
        component: StudentHomeView,
        meta: { title: '学生工作台' },
      },
      { path: 'exams/:examId', component: StudentExamDetailView, meta: { title: '考试详情' } },
      { path: 'paper/:submissionId', component: StudentPaperView, meta: { title: '在线答题' } },
      { path: 'results', component: StudentResultsView, meta: { title: '我的成绩' } },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/login',
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.afterEach((to) => {
  document.title = `${to.meta.title || '智能考试管理系统'} - 智能考试管理系统`
})

router.beforeEach(async (to) => {
  if (to.meta.requiresAuth && !authState.currentUser) await restoreSession()
  if (to.meta.requiresAuth && !authState.currentUser) return { name: 'login' }
  if (to.meta.role && authState.currentUser?.role !== to.meta.role) return authState.currentUser?.role === 'TEACHER' ? '/teacher' : '/student'
  if (to.name === 'login' && authState.currentUser) return authState.currentUser.role === 'TEACHER' ? '/teacher' : '/student'
})

export default router
