<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { changePassword } from '../../api/auth'
import { authState, restoreSession } from '../../auth/auth'

const router = useRouter()
const currentPassword = ref(''), newPassword = ref(''), confirmPassword = ref('')
const error = ref(''), busy = ref(false)
function home() {
  return authState.currentUser?.role === 'ADMIN' ? '/admin'
    : authState.currentUser?.role === 'TEACHER' ? '/teacher' : '/student'
}

async function submit() {
  error.value = ''
  if (newPassword.value.length < 8) { error.value = '新密码至少8位'; return }
  if (newPassword.value !== confirmPassword.value) { error.value = '两次输入的新密码不一致'; return }
  busy.value = true
  try {
    await changePassword({ currentPassword: currentPassword.value, newPassword: newPassword.value })
    await restoreSession()
    await router.replace(home())
  } catch (e) { error.value = e.message || '修改密码失败' }
  finally { busy.value = false }
}
</script>

<template>
  <section class="surface password-form">
    <div>
      <h1>{{ authState.currentUser?.mustChangePassword ? '首次登录，请修改密码' : '修改密码' }}</h1>
      <p class="muted">新密码至少8位，修改成功后旧密码立即失效。</p>
    </div>
    <p v-if="error" class="error-message">{{ error }}</p>
    <form class="editor-form" @submit.prevent="submit">
      <label>当前密码<input v-model="currentPassword" type="password" autocomplete="current-password" required></label>
      <label>新密码<input v-model="newPassword" type="password" autocomplete="new-password" minlength="8" required></label>
      <label>确认新密码<input v-model="confirmPassword" type="password" autocomplete="new-password" minlength="8" required></label>
      <button class="primary" :disabled="busy">{{ busy ? '正在修改…' : '确认修改密码' }}</button>
    </form>
  </section>
</template>
