<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { login } from '../api/auth'
import { setSession } from '../auth/auth'

const username = ref('')
const password = ref('')
const notice = ref('')
const loading = ref(false)
const router = useRouter()

async function handleSubmit() {
  notice.value = ''
  if (!username.value || !password.value) { notice.value = '请输入用户名和密码'; return }
  loading.value = true
  try { const result = await login({ username: username.value, password: password.value }); setSession(result.data.token, result.data.user); await router.push(result.data.user.role === 'TEACHER' ? '/teacher' : '/student') }
  catch (error) { notice.value = error.message || '登录失败，请稍后重试' }
  finally { loading.value = false }
}
</script>

<template>
  <main class="login-page">
    <section class="login-card">
      <p class="eyebrow">SMART EXAM</p>
      <h1>智能考试管理系统</h1>
      <p class="muted">教师与学生统一登录入口</p>

      <form class="login-form" @submit.prevent="handleSubmit">
        <label>
          用户名
          <input v-model.trim="username" autocomplete="username" placeholder="请输入用户名" />
        </label>
        <label>
          密码
          <input
            v-model="password"
            type="password"
            autocomplete="current-password"
            placeholder="请输入密码"
          />
        </label>
        <button type="submit" :disabled="loading">{{ loading ? '登录中…' : '登录' }}</button>
      </form>

      <p v-if="notice" class="notice">{{ notice }}</p>
    </section>
  </main>
</template>
