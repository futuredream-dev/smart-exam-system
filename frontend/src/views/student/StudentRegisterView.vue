<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { registerStudent } from '../../api/auth'

const router = useRouter()
const username = ref('')
const name = ref('')
const password = ref('')
const confirmPassword = ref('')
const notice = ref('')
const loading = ref(false)

async function handleSubmit() {
  notice.value = ''
  if (!username.value.trim()) { notice.value = '请输入学号'; return }
  if (!name.value.trim()) { notice.value = '请输入姓名'; return }
  if (password.value.length < 8) { notice.value = '密码至少8位'; return }
  if (password.value !== confirmPassword.value) { notice.value = '两次输入的密码不一致'; return }

  loading.value = true
  try {
    await registerStudent({
      username: username.value.trim(),
      name: name.value.trim(),
      password: password.value,
    })
    await router.push({ name: 'login', query: { registered: '1' } })
  } catch (error) {
    notice.value = error.errorCode === 'ACCOUNT_EXISTS'
      ? '该学号已存在，请直接登录或联系教师'
      : error.message || '注册失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <section class="login-card">
      <p class="eyebrow">STUDENT REGISTER</p>
      <h1>学生注册</h1>
      <p class="muted">创建学生账号后，请返回统一登录页登录</p>

      <form class="login-form" @submit.prevent="handleSubmit">
        <label>
          学号/登录账号
          <input v-model="username" autocomplete="username" placeholder="请输入学号" />
        </label>
        <label>
          姓名
          <input v-model="name" autocomplete="name" placeholder="请输入姓名" />
        </label>
        <label>
          密码
          <input v-model="password" type="password" autocomplete="new-password" placeholder="至少8位" />
        </label>
        <label>
          确认密码
          <input v-model="confirmPassword" type="password" autocomplete="new-password" placeholder="请再次输入密码" />
        </label>
        <button type="submit" :disabled="loading">{{ loading ? '注册中…' : '注册' }}</button>
      </form>

      <p v-if="notice" class="notice">{{ notice }}</p>
      <p class="auth-switch">已有账号？<RouterLink to="/login">返回登录</RouterLink></p>
    </section>
  </main>
</template>
