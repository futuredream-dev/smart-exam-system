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
      <nav v-if="authState.currentUser?.role === 'TEACHER'" aria-label="教师导航">
        <RouterLink to="/teacher" :class="{ active: route.path === '/teacher' }"><span>▦</span>工作台</RouterLink>
        <RouterLink to="/teacher/courses" :class="{ active: route.path.startsWith('/teacher/courses') }"><span>▤</span>课程管理</RouterLink>
        <RouterLink to="/teacher/exams" :class="{ active: route.path.startsWith('/teacher/exams') }"><span>▣</span>考试管理</RouterLink>
      </nav>
      <nav v-else><RouterLink to="/student" class="active">学生工作台</RouterLink></nav>
      <div class="sidebar-footer">V1.0 · 教学工作空间<br /><small>课程 · 考试 · 试卷设计</small></div>
    </aside>
    <div class="workspace-main">
      <header class="workspace-header"><div>{{ portalName }} <span class="muted"> / {{ route.meta.title }}</span></div><div class="actions"><span class="avatar">{{ authState.currentUser?.name?.slice(0, 1) }}</span><span>{{ authState.currentUser?.name }}<small class="user-account">{{ authState.currentUser?.username }}</small></span><button class="ghost" @click="logout">退出登录</button></div></header>
      <main class="workspace-content"><RouterView :key="route.fullPath" /></main>
    </div>
  </div>
</template>
