<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'

import {
  apiErrorMessage,
  deleteVideo,
  listVideos,
  updateVideoTitle,
} from '@/services/video'
import type { Video } from '@/types/video'

const loading = ref(true)
const videos = ref<Video[]>([])
const errorMessage = ref('')
const searchInput = ref('')
const keyword = ref('')
const page = ref(1)
const size = ref(10)
const total = ref(0)
const pages = ref(0)

async function loadVideos() {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listVideos(page.value, size.value, keyword.value)
    videos.value = result.items
    page.value = result.page
    size.value = result.size
    total.value = result.total
    pages.value = result.pages
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, '暂时无法加载视频列表，请确认后端服务已启动。')
  } finally {
    loading.value = false
  }
}

async function search() {
  keyword.value = searchInput.value.trim()
  page.value = 1
  await loadVideos()
}

async function changePage(nextPage: number) {
  page.value = nextPage
  await loadVideos()
}

async function editTitle(video: Video) {
  try {
    const { value } = await ElMessageBox.prompt('请输入新的视频标题', '修改标题', {
      inputValue: video.title,
      inputValidator: (title) => {
        const normalized = title.trim()
        return normalized.length > 0 && normalized.length <= 255
          ? true
          : '标题长度必须为 1 至 255 个字符'
      },
      confirmButtonText: '保存',
      cancelButtonText: '取消',
    })
    const updated = await updateVideoTitle(video.id, value)
    const index = videos.value.findIndex((item) => item.id === video.id)
    if (index >= 0) videos.value[index] = updated
    ElMessage.success('标题已更新')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(apiErrorMessage(error, '标题更新失败，请稍后重试。'))
  }
}

async function removeVideo(video: Video) {
  try {
    await ElMessageBox.confirm(
      `删除“${video.title}”后，字幕和 AI 总结也会被删除，是否继续？`,
      '确认删除视频',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
    await deleteVideo(video.id)
    if (videos.value.length === 1 && page.value > 1) page.value -= 1
    await loadVideos()
    ElMessage.success('视频已删除')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(apiErrorMessage(error, '视频删除失败，请稍后重试。'))
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
        <p class="page-description">视频与分析结果仅对当前登录用户可见。</p>
      </div>
      <RouterLink class="primary-action" to="/upload">上传新视频</RouterLink>
    </div>

    <form class="video-search" @submit.prevent="search">
      <el-input
        v-model="searchInput"
        clearable
        maxlength="255"
        placeholder="按视频标题搜索"
        @clear="search"
      />
      <el-button type="primary" :loading="loading" @click="search">搜索</el-button>
    </form>

    <div v-if="errorMessage" class="notice notice--error">
      <span>{{ errorMessage }}</span>
      <button type="button" @click="loadVideos">重试</button>
    </div>

    <div v-if="loading" class="content-panel loading-panel">
      <el-skeleton :rows="5" animated />
    </div>

    <div v-else-if="videos.length === 0 && !errorMessage" class="content-panel empty-state">
      <div class="empty-icon">□</div>
      <h2>{{ keyword ? '没有匹配的视频' : '还没有视频' }}</h2>
      <p>{{ keyword ? '尝试使用其他标题关键词。' : '上传一个 MP4，开始建立你的视频资料库。' }}</p>
      <RouterLink v-if="!keyword" class="primary-action" to="/upload">选择 MP4</RouterLink>
    </div>

    <div v-else-if="videos.length" class="video-grid">
      <article v-for="video in videos" :key="video.id" class="video-card video-card--managed">
        <RouterLink class="video-card__link" :to="`/videos/${video.id}`">
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
        <div class="video-card__actions">
          <el-button text @click="editTitle(video)">修改标题</el-button>
          <el-button text type="danger" @click="removeVideo(video)">删除</el-button>
        </div>
      </article>
    </div>

    <div v-if="total > 0" class="video-pagination">
      <span>共 {{ total }} 个视频</span>
      <el-pagination
        background
        layout="prev, pager, next"
        :current-page="page"
        :page-size="size"
        :total="total"
        :page-count="pages"
        @current-change="changePage"
      />
    </div>
  </section>
</template>
