<script setup lang="ts">
import { RouterLink, RouterView, useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()

async function logout() {
  auth.clearSession()
  await router.replace('/login')
}
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <RouterLink class="brand" to="/videos" aria-label="VideoAgent 视频列表">
        <span class="brand-mark">V</span>
        <span>VideoAgent</span>
      </RouterLink>

      <nav v-if="auth.isAuthenticated" class="app-nav" aria-label="主导航">
        <span class="nav-user">{{ auth.user?.username }}</span>
        <RouterLink to="/videos">视频库</RouterLink>
        <RouterLink class="nav-upload" to="/upload">上传视频</RouterLink>
        <button class="nav-logout" type="button" @click="logout">退出登录</button>
      </nav>
    </header>

    <main class="app-main">
      <RouterView />
    </main>

    <footer class="app-footer">
      <span>VideoAgent</span>
    </footer>
  </div>
</template>
