<script setup>
import { useRouter } from 'vue-router'
import { authState, clearSession } from '../../auth/auth'
const router = useRouter()
function logout() { clearSession(); router.push('/login') }
</script>
<template>
  <section>
    <p class="eyebrow">TEACHER</p>
    <h1>教师工作台</h1>
    <p>当前用户：{{ authState.currentUser?.name }}（{{ authState.currentUser?.username }}）</p>
    <p>角色：教师</p><button @click="logout">退出登录</button>
    <p class="muted">课程管理将在下一阶段实现。</p>
  </section>
</template>
