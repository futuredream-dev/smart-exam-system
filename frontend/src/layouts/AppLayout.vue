<script setup>
import { useRoute, useRouter } from 'vue-router'
import { authState, clearSession } from '../auth/auth'
import '../styles/workspace.css'
defineProps({ portalName: { type: String, required: true } })
const route = useRoute(), router = useRouter()
function logout() { clearSession(); router.replace('/login') }
</script>

<template>
  <div class="workspace-shell">
    <aside class="workspace-sidebar">
      <div class="brand"><span class="brand-mark">考</span><div><strong>智能考试管理系统</strong><small>SMART EXAM · {{ portalName }}</small></div></div>
      <p class="nav-caption">教学管理</p>
      <nav v-if="authState.currentUser?.role === 'ADMIN'" aria-label="管理员导航">
        <template v-if="!authState.currentUser?.mustChangePassword"><RouterLink to="/admin" :class="{ active: route.path === '/admin' }"><span>▤</span>教师账号</RouterLink></template>
        <RouterLink to="/admin/change-password" :class="{active: route.path.startsWith('/admin/change-password')}"><span>⌁</span>修改密码</RouterLink>
      </nav>
      <nav v-else-if="authState.currentUser?.role === 'TEACHER'" aria-label="教师导航">
        <template v-if="!authState.currentUser?.mustChangePassword">
        <RouterLink to="/teacher" :class="{ active: route.path === '/teacher' }"><span>▦</span>工作台</RouterLink>
        <RouterLink to="/teacher/courses" :class="{ active: route.path.startsWith('/teacher/courses') }"><span>▤</span>课程管理</RouterLink>
        <RouterLink to="/teacher/exams" :class="{ active: route.path.startsWith('/teacher/exams') }"><span>▣</span>考试管理</RouterLink>
        <RouterLink to="/teacher/grading" :class="{ active: route.path.startsWith('/teacher/grading') }"><span>✓</span>考试阅卷</RouterLink>
        </template>
        <RouterLink to="/teacher/change-password" :class="{active: route.path.startsWith('/teacher/change-password')}"><span>⌁</span>修改密码</RouterLink>
      </nav>
      <nav v-else><template v-if="!authState.currentUser?.mustChangePassword"><RouterLink to="/student" :class="{active: route.path === '/student' || route.path.startsWith('/student/exams') || route.path.startsWith('/student/paper')}"><span>▣</span>我的考试</RouterLink><RouterLink to="/student/results" :class="{active: route.path.startsWith('/student/results')}"><span>✓</span>我的成绩</RouterLink></template><RouterLink to="/student/change-password" :class="{active: route.path.startsWith('/student/change-password')}"><span>⌁</span>修改密码</RouterLink></nav>
      <div class="sidebar-footer">V1.0 · 教学工作空间<br /><small>考试 · 答题 · 阅卷 · 成绩</small></div>
    </aside>
    <div class="workspace-main">
      <header class="workspace-header"><div>{{ portalName }} <span class="muted"> / {{ route.meta.title }}</span></div><div class="actions"><span class="avatar">{{ authState.currentUser?.name?.slice(0, 1) }}</span><span>{{ authState.currentUser?.name }}<small class="user-account">{{ authState.currentUser?.username }}</small></span><button class="ghost" @click="logout">退出登录</button></div></header>
      <main class="workspace-content"><RouterView :key="route.fullPath" /></main>
    </div>
  </div>
</template>
