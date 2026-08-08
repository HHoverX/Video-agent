<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import { apiErrorMessage, getVideo } from '@/services/video'
import type { Video } from '@/types/video'

const route = useRoute()
const loading = ref(true)
const video = ref<Video | null>(null)
const errorMessage = ref('')

function formatBytes(bytes: number) {
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'long',
    timeStyle: 'short',
  }).format(new Date(value))
}

async function loadVideo() {
  loading.value = true
  try {
    video.value = await getVideo(Number(route.params.id))
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, '视频详情加载失败。')
  } finally {
    loading.value = false
  }
}

onMounted(loadVideo)
</script>

<template>
  <section class="page-section detail-page">
    <RouterLink class="back-link" to="/">← 返回视频库</RouterLink>

    <div v-if="loading" class="content-panel loading-panel">
      <el-skeleton :rows="6" animated />
    </div>

    <div v-else-if="errorMessage" class="notice notice--error">{{ errorMessage }}</div>

    <template v-else-if="video">
      <div class="detail-hero">
        <div class="detail-visual"><span>▶</span></div>
        <div class="detail-copy">
          <p class="eyebrow">VIDEO #{{ video.id }}</p>
          <h1>{{ video.title }}</h1>
          <p>{{ video.originalFilename }}</p>
          <span class="status-badge status-badge--large">已上传</span>
        </div>
      </div>

      <div class="detail-grid">
        <div class="detail-item">
          <span>文件大小</span>
          <strong>{{ formatBytes(video.fileSize) }}</strong>
        </div>
        <div class="detail-item">
          <span>MIME 类型</span>
          <strong>{{ video.mimeType }}</strong>
        </div>
        <div class="detail-item">
          <span>视频时长</span>
          <strong>{{ video.durationSeconds ? `${video.durationSeconds} 秒` : '待后续媒体阶段提取' }}</strong>
        </div>
        <div class="detail-item">
          <span>上传时间</span>
          <strong>{{ formatDate(video.createdAt) }}</strong>
        </div>
      </div>

      <div class="milestone-boundary">
        <strong>上传已完成</strong>
        <p>视频分析将在下一里程碑开放；当前只保存原始视频与元数据。</p>
      </div>
    </template>
  </section>
</template>
