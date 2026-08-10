<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { login } from '@/services/auth'
import { apiErrorMessage } from '@/services/video'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const username = ref('')
const password = ref('')
const submitting = ref(false)
const errorMessage = ref('')

async function submit() {
  if (username.value.trim().length < 3 || password.value.length < 8) {
    errorMessage.value = '用户名至少 3 个字符，密码至少 8 个字符。'
    return
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    auth.setSession(await login({ username: username.value.trim(), password: password.value }))
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/videos'
    await router.replace(redirect)
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, '登录失败，请稍后重试。')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section class="auth-page">
    <div class="content-panel auth-panel">
      <p class="eyebrow">WELCOME BACK</p>
      <h1>登录 VideoAgent</h1>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model="username" maxlength="50" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="password"
            type="password"
            maxlength="72"
            show-password
            autocomplete="current-password"
            @keyup.enter="submit"
          />
        </el-form-item>
        <p v-if="errorMessage" class="field-error">{{ errorMessage }}</p>
        <el-button class="submit-button" type="primary" :loading="submitting" @click="submit">
          登录
        </el-button>
      </el-form>
      <p class="auth-switch">还没有账号？<RouterLink to="/register">立即注册</RouterLink></p>
    </div>
  </section>
</template>
