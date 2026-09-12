<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { login } from '../api/auth'
import { setSession } from '../auth/auth'

const username = ref('')
const password = ref('')
const notice = ref('')
const loading = ref(false)
const router = useRouter()
const route = useRoute()
const registrationNotice = computed(() => route.query.registered === '1'
  ? '注册成功，请使用学号和密码登录'
  : '')
function destination(user) {
  if (user.mustChangePassword) return `/${user.role.toLowerCase()}/change-password`
  return user.role === 'ADMIN' ? '/admin' : user.role === 'TEACHER' ? '/teacher' : '/student'
}

async function handleSubmit() {
  notice.value = ''
  if (!username.value || !password.value) { notice.value = '请输入用户名和密码'; return }
  loading.value = true
  try { const result = await login({ username: username.value, password: password.value }); setSession(result.data.token, result.data.user); await router.push(destination(result.data.user)) }
  catch (error) { notice.value = error.errorCode === 'ACCOUNT_DISABLED' ? '账号已停用，请联系管理员' : error.message || '登录失败，请稍后重试' }
  finally { loading.value = false }
}
</script>

<template>
  <main class="login-page">
    <section class="login-card">
      <p class="eyebrow">SMART EXAM</p>
      <h1>智能考试管理系统</h1>
      <p class="muted">管理员、教师与学生统一登录入口</p>
      <p v-if="registrationNotice" class="success-notice">{{ registrationNotice }}</p>

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
      <p class="auth-switch">还没有学生账号？<RouterLink to="/register/student">学生注册</RouterLink></p>
    </section>
  </main>
</template>
