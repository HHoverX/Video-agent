<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'

import { apiErrorMessage, listVideos } from '@/services/video'
import type { Video } from '@/types/video'

const loading = ref(true)
const videos = ref<Video[]>([])
const errorMessage = ref('')

async function loadVideos() {
  loading.value = true
  errorMessage.value = ''
  try {
    videos.value = await listVideos()
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, '暂时无法加载视频列表，请确认后端服务已启动。')
  } finally {
    loading.value = false
  }
}

function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

onMounted(loadVideos)
</script>

<template>
  <section class="page-section">
    <div class="page-heading">
      <div>
        <p class="eyebrow">VIDEO LIBRARY</p>
        <h1>你的视频</h1>
        <p class="page-description">上传的 MP4 会保存在 MinIO，元数据同步写入 MySQL。</p>
      </div>
      <RouterLink class="primary-action" to="/upload">上传新视频</RouterLink>
    </div>

    <div v-if="errorMessage" class="notice notice--error">
      <span>{{ errorMessage }}</span>
      <button type="button" @click="loadVideos">重试</button>
    </div>

    <div v-if="loading" class="content-panel loading-panel">
      <el-skeleton :rows="5" animated />
    </div>

    <div v-else-if="videos.length === 0 && !errorMessage" class="content-panel empty-state">
      <div class="empty-icon">▶</div>
      <h2>还没有视频</h2>
      <p>上传一个 MP4，开始建立你的视频资料库。</p>
      <RouterLink class="primary-action" to="/upload">选择 MP4</RouterLink>
    </div>

    <div v-else-if="videos.length" class="video-grid">
      <RouterLink
        v-for="video in videos"
        :key="video.id"
        class="video-card"
        :to="`/videos/${video.id}`"
      >
        <div class="video-card__visual">
          <span class="play-mark">▶</span>
          <span class="status-badge">已上传</span>
        </div>
        <div class="video-card__body">
          <h2>{{ video.title }}</h2>
          <p>{{ video.originalFilename }}</p>
          <div class="video-meta">
            <span>{{ formatBytes(video.fileSize) }}</span>
            <span>{{ formatDate(video.createdAt) }}</span>
          </div>
        </div>
      </RouterLink>
    </div>
  </section>
</template>
