<script setup>
defineProps({
  portalName: {
    type: String,
    required: true,
  },
})
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <div>
        <strong>智能考试管理系统</strong>
        <span class="portal-name">{{ portalName }}</span>
      </div>
      <RouterLink to="/login">返回登录</RouterLink>
    </header>

    <div class="app-body">
      <aside class="app-sidebar">
        <p>导航区域</p>
        <small>后续阶段按业务模块扩展</small>
      </aside>
      <main class="app-content">
        <RouterView />
      </main>
    </div>
  </div>
</template>
