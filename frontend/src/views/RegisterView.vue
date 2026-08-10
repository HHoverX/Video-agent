<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import { register } from '@/services/auth'
import { apiErrorMessage } from '@/services/video'

const router = useRouter()
const username = ref('')
const password = ref('')
const submitting = ref(false)
const errorMessage = ref('')

async function submit() {
  if (username.value.trim().length < 3 || username.value.trim().length > 50) {
    errorMessage.value = '用户名长度必须为 3 至 50 个字符。'
    return
  }
  if (password.value.length < 8 || password.value.length > 72) {
    errorMessage.value = '密码长度必须为 8 至 72 个字符。'
    return
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    await register({ username: username.value.trim(), password: password.value })
    ElMessage.success('注册成功，请登录')
    await router.replace('/login')
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, '注册失败，请稍后重试。')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section class="auth-page">
    <div class="content-panel auth-panel">
      <p class="eyebrow">CREATE ACCOUNT</p>
      <h1>注册 VideoAgent</h1>
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
            autocomplete="new-password"
            @keyup.enter="submit"
          />
        </el-form-item>
        <p v-if="errorMessage" class="field-error">{{ errorMessage }}</p>
        <el-button class="submit-button" type="primary" :loading="submitting" @click="submit">
          注册
        </el-button>
      </el-form>
      <p class="auth-switch">已有账号？<RouterLink to="/login">返回登录</RouterLink></p>
    </div>
  </section>
</template>
