<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { changePassword } from '../../api/auth'
import { authState, expireSession, sessionIdentityVersion } from '../../auth/auth'

const router = useRouter()
const currentPassword = ref(''), newPassword = ref(''), confirmPassword = ref('')
const error = ref(''), busy = ref(false)
async function submit() {
  error.value = ''
  if (newPassword.value.length < 8) { error.value = '新密码至少8位'; return }
  if (newPassword.value !== confirmPassword.value) { error.value = '两次输入的新密码不一致'; return }
  busy.value = true
  const submittedToken = authState.token
  const submittedIdentity = sessionIdentityVersion()
  try {
    await changePassword({ currentPassword: currentPassword.value, newPassword: newPassword.value })
    if (submittedIdentity !== sessionIdentityVersion()) return
    expireSession(submittedToken)
    currentPassword.value = newPassword.value = confirmPassword.value = ''
    await router.replace({ path: '/login', query: { passwordChanged: '1' } })
  } catch (e) { error.value = e.message || '修改密码失败' }
  finally { busy.value = false }
}
</script>

<template>
  <section class="surface password-form">
    <div>
      <h1>{{ authState.currentUser?.mustChangePassword ? '首次登录，请修改密码' : '修改密码' }}</h1>
      <p v-if="authState.currentUser?.role === 'STUDENT' && authState.currentUser?.mustChangePassword" class="muted">为保护账号安全，请将临时密码改为自己的密码，完成后请重新登录。</p>
      <p class="muted">新密码至少8位。修改成功后旧密码和已有登录状态立即失效，请使用新密码重新登录。</p>
    </div>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <form class="editor-form" @submit.prevent="submit">
      <label>当前密码<input v-model="currentPassword" type="password" autocomplete="current-password" required></label>
      <label>新密码<input v-model="newPassword" type="password" autocomplete="new-password" minlength="8" required></label>
      <label>确认新密码<input v-model="confirmPassword" type="password" autocomplete="new-password" minlength="8" required></label>
      <button class="primary" :disabled="busy">{{ busy ? '正在修改…' : '确认修改密码' }}</button>
    </form>
  </section>
</template>
