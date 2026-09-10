import { createRouter, createWebHistory } from 'vue-router'
import AppLayout from '../layouts/AppLayout.vue'
import LoginView from '../views/LoginView.vue'
import StudentHomeView from '../views/student/StudentHomeView.vue'
import TeacherHomeView from '../views/teacher/TeacherHomeView.vue'
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
