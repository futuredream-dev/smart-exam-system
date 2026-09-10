<script setup>
import { useRouter } from 'vue-router'
import { authState, clearSession } from '../../auth/auth'
const router = useRouter()
function logout() { clearSession(); router.push('/login') }
</script>
<template>
  <section>
    <p class="eyebrow">STUDENT</p>
    <h1>学生工作台</h1>
    <p>当前用户：{{ authState.currentUser?.name }}（{{ authState.currentUser?.username }}）</p>
    <p>角色：学生</p><button @click="logout">退出登录</button>
    <p class="muted">考试功能将在后续阶段实现。</p>
  </section>
</template>
