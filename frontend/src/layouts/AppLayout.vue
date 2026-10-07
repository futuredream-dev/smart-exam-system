<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { authState, clearSession } from '../auth/auth'
import '../styles/workspace.css'
import '../styles/teacher.css'
import '../styles/student.css'
import '../styles/admin.css'

defineProps({ portalName: { type: String, required: true } })

const route = useRoute()
const router = useRouter()
const role = computed(() => authState.currentUser?.role)
const roleClass = computed(() => `role-${role.value?.toLowerCase() || 'guest'}`)
const accountPasswordPath = computed(() => ({
  ADMIN: '/admin/change-password',
  TEACHER: '/teacher/change-password',
  STUDENT: '/student/change-password',
})[role.value] || '')
const navCaption = computed(() => ({
  ADMIN: '平台管理', TEACHER: '教学管理', STUDENT: '学生考试',
})[role.value] || '')
const sidebarContext = computed(() => ({
  ADMIN: '账号 · 课程 · 考试监管',
  TEACHER: '课程 · 考试 · 阅卷 · 成绩',
  STUDENT: '考试 · 答题 · 成绩',
})[role.value] || '')
const teacherGradingActive = computed(() => route.path === '/teacher/grading-tasks'
  || /^\/teacher\/exams\/[^/]+\/grading\/submissions\/[^/]+\/?$/.test(route.path)
  || route.path.startsWith('/teacher/grading/submissions/'))

function logout() {
  clearSession()
  router.replace('/login')
}
</script>

<template>
  <div :class="['workspace-shell', roleClass]">
    <aside class="workspace-sidebar">
      <div class="brand">
        <span class="brand-mark" aria-hidden="true">考</span>
        <div><strong>智能考试管理系统</strong><small>{{ portalName }}</small></div>
      </div>
      <p class="nav-caption">{{ navCaption }}</p>

      <nav v-if="role === 'ADMIN'" aria-label="管理员导航">
        <template v-if="!authState.currentUser?.mustChangePassword">
          <RouterLink to="/admin" :class="{ active: route.path === '/admin' }"><span aria-hidden="true">▦</span>系统概览</RouterLink>
          <RouterLink to="/admin/teachers" :class="{ active: route.path === '/admin/teachers' }"><span aria-hidden="true">▤</span>教师管理</RouterLink>
          <RouterLink to="/admin/students" :class="{ active: route.path === '/admin/students' }"><span aria-hidden="true">♙</span>学生管理</RouterLink>
          <RouterLink to="/admin/courses" :class="{ active: route.path === '/admin/courses' }"><span aria-hidden="true">▥</span>课程监管</RouterLink>
          <RouterLink to="/admin/exams" :class="{ active: route.path === '/admin/exams' }"><span aria-hidden="true">▣</span>考试监管</RouterLink>
        </template>
      </nav>
      <nav v-else-if="role === 'TEACHER'" aria-label="教师导航">
        <template v-if="!authState.currentUser?.mustChangePassword">
          <RouterLink to="/teacher" :class="{ active: route.path === '/teacher' }"><span aria-hidden="true">▦</span>工作台</RouterLink>
          <RouterLink to="/teacher/exams" :class="{ active: route.path.startsWith('/teacher/exams') && !teacherGradingActive }"><span aria-hidden="true">▣</span>考试</RouterLink>
          <RouterLink to="/teacher/grading-tasks" :class="{ active: teacherGradingActive }"><span aria-hidden="true">✓</span>阅卷任务</RouterLink>
          <RouterLink to="/teacher/ai-evaluation" :class="{ active: route.path === '/teacher/ai-evaluation' }"><span aria-hidden="true">◫</span>AI评分分析</RouterLink>
          <RouterLink to="/teacher/question-banks" :class="{ active: route.path.startsWith('/teacher/question-banks') }"><span aria-hidden="true">▥</span>题库</RouterLink>
          <RouterLink to="/teacher/courses" :class="{ active: route.path.startsWith('/teacher/courses') }"><span aria-hidden="true">▤</span>课程</RouterLink>
        </template>
      </nav>
      <nav v-else aria-label="学生导航">
        <template v-if="!authState.currentUser?.mustChangePassword">
          <RouterLink to="/student" :class="{ active: route.path === '/student' || route.path.startsWith('/student/exams') || route.path.startsWith('/student/paper') }"><span aria-hidden="true">▣</span>我的考试</RouterLink>
          <RouterLink to="/student/results" :class="{ active: route.path.startsWith('/student/results') }"><span aria-hidden="true">✓</span>我的成绩</RouterLink>
        </template>
        <RouterLink to="/student/change-password" :class="{ active: route.path.startsWith('/student/change-password') }"><span aria-hidden="true">⌁</span>修改密码</RouterLink>
      </nav>

      <div class="sidebar-footer">{{ sidebarContext }}</div>
    </aside>

    <div class="workspace-main">
      <header class="workspace-header">
        <div class="workspace-location">{{ portalName }} <span class="muted">/ {{ route.meta.title }}</span></div>
        <div class="actions">
          <span class="avatar" aria-hidden="true">{{ authState.currentUser?.name?.slice(0, 1) }}</span>
          <span>{{ authState.currentUser?.name }}<small class="user-account">{{ authState.currentUser?.username }}</small></span>
          <RouterLink v-if="accountPasswordPath" class="ghost" :to="accountPasswordPath">修改密码</RouterLink>
          <button type="button" class="ghost" @click="logout">退出登录</button>
        </div>
      </header>
      <main class="workspace-content"><RouterView :key="route.fullPath" /></main>
    </div>
  </div>
</template>
