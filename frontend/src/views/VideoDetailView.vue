<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import { getAnalysisTask, startAnalysis } from '@/services/analysis'
import { apiErrorMessage, getVideo } from '@/services/video'
import type { AnalysisTask, AnalysisStatus } from '@/types/analysis'
import type { Video } from '@/types/video'

const POLL_INTERVAL_MILLIS = 500

const route = useRoute()
const loading = ref(true)
const video = ref<Video | null>(null)
const errorMessage = ref('')
const startingAnalysis = ref(false)
const pollingAnalysis = ref(false)
const analysisTask = ref<AnalysisTask | null>(null)
const analysisError = ref('')
let pollTimer: number | undefined

const analysisStatusLabel = computed(() => {
  const labels: Record<AnalysisStatus, string> = {
    PENDING: '排队中',
    PROCESSING: '处理中',
    SUCCESS: '已完成',
    FAILED: '失败',
  }
  return analysisTask.value ? labels[analysisTask.value.status] : '尚未开始'
})

const progressStatus = computed(() => {
  if (analysisTask.value?.status === 'SUCCESS') return 'success'
  if (analysisTask.value?.status === 'FAILED') return 'exception'
  return undefined
})

const analysisActive = computed(
  () => analysisTask.value?.status === 'PENDING' || analysisTask.value?.status === 'PROCESSING',
)

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

function clearPollTimer() {
  if (pollTimer !== undefined) {
    window.clearTimeout(pollTimer)
    pollTimer = undefined
  }
}

function schedulePoll(taskId: number) {
  clearPollTimer()
  pollTimer = window.setTimeout(() => pollAnalysis(taskId), POLL_INTERVAL_MILLIS)
}

async function pollAnalysis(taskId: number) {
  pollingAnalysis.value = true
  try {
    analysisTask.value = await getAnalysisTask(taskId)
    analysisError.value = ''
    if (analysisActive.value) {
      schedulePoll(taskId)
    }
  } catch (error) {
    analysisError.value = apiErrorMessage(error, '分析状态加载失败，请稍后重试。')
  } finally {
    pollingAnalysis.value = false
  }
}

async function handleStartAnalysis() {
  if (!video.value || startingAnalysis.value || analysisActive.value) return

  startingAnalysis.value = true
  analysisError.value = ''
  try {
    const started = await startAnalysis(video.value.id)
    analysisTask.value = {
      ...started,
      stage: 'QUEUED',
      progress: 0,
      message: '任务已进入队列',
      errorCode: null,
      errorMessage: null,
      createdAt: new Date().toISOString(),
      startedAt: null,
      finishedAt: null,
    }
    schedulePoll(started.taskId)
  } catch (error) {
    analysisError.value = apiErrorMessage(error, '无法发起分析，请稍后重试。')
  } finally {
    startingAnalysis.value = false
  }
}

onMounted(loadVideo)
onBeforeUnmount(clearPollTimer)
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

      <div class="analysis-panel content-panel">
        <div class="analysis-panel__heading">
          <div>
            <p class="eyebrow">MILESTONE 3 · ASYNC FRAMEWORK</p>
            <h2>AI 分析任务</h2>
            <p>通过 RocketMQ 异步执行模拟阶段；本阶段不会生成真实分析内容。</p>
          </div>
          <el-button
            class="analysis-start-button"
            :disabled="analysisActive || analysisTask?.status === 'SUCCESS'"
            :loading="startingAnalysis"
            @click="handleStartAnalysis"
          >
            {{ analysisActive ? '分析进行中' : analysisTask?.status === 'SUCCESS' ? '分析已完成' : '开始 AI 分析' }}
          </el-button>
        </div>

        <div v-if="analysisError" class="notice notice--error analysis-notice">
          {{ analysisError }}
        </div>

        <div v-if="analysisTask" class="analysis-progress" aria-live="polite">
          <div class="analysis-progress__meta">
            <div>
              <span>任务 #{{ analysisTask.taskId }}</span>
              <strong>{{ analysisStatusLabel }}</strong>
            </div>
            <span>{{ analysisTask.message }}</span>
          </div>
          <el-progress
            :percentage="analysisTask.progress"
            :status="progressStatus"
            :stroke-width="10"
          />
          <p v-if="analysisTask.status === 'FAILED'" class="analysis-failure">
            {{ analysisTask.errorMessage || '任务处理失败。' }}
          </p>
          <p v-else-if="pollingAnalysis" class="analysis-polling">正在刷新实时进度…</p>
        </div>

        <div v-else class="analysis-empty">
          尚未创建分析任务。点击按钮后，接口会立即返回任务编号并开始轮询状态。
        </div>
      </div>
    </template>
  </section>
</template>
