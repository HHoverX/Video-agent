<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import { getAnalysisTask, startAnalysis } from '@/services/analysis'
import { getVideoTranscript } from '@/services/transcript'
import { apiErrorMessage, getVideo } from '@/services/video'
import type { AnalysisTask, AnalysisStatus } from '@/types/analysis'
import type { TranscriptSegment } from '@/types/transcript'
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
const transcript = ref<TranscriptSegment[]>([])
const transcriptLoading = ref(false)
const transcriptError = ref('')
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
const analysisComplete = computed(
  () => analysisTask.value?.status === 'SUCCESS' || transcript.value.length > 0,
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

function formatTimestamp(milliseconds: number) {
  const totalSeconds = Math.floor(milliseconds / 1000)
  const hours = Math.floor(totalSeconds / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const seconds = totalSeconds % 60
  const base = `${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}`
  return hours > 0 ? `${hours.toString().padStart(2, '0')}:${base}` : base
}

async function loadTranscript() {
  const videoId = Number(route.params.id)
  transcriptLoading.value = true
  try {
    transcript.value = await getVideoTranscript(videoId)
    transcriptError.value = ''
  } catch (error) {
    transcriptError.value = apiErrorMessage(error, '字幕加载失败，请稍后重试。')
  } finally {
    transcriptLoading.value = false
  }
}

async function loadVideo() {
  loading.value = true
  try {
    video.value = await getVideo(Number(route.params.id))
    await loadTranscript()
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
    const current = await getAnalysisTask(taskId)
    analysisTask.value = current
    analysisError.value = ''
    if (current.status === 'SUCCESS') {
      await loadTranscript()
    } else if (analysisActive.value) {
      schedulePoll(taskId)
    }
  } catch (error) {
    analysisError.value = apiErrorMessage(error, '分析状态加载失败，请稍后重试。')
  } finally {
    pollingAnalysis.value = false
  }
}

async function handleStartAnalysis() {
  if (!video.value || startingAnalysis.value || analysisActive.value || analysisComplete.value) return

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
            <p class="eyebrow">MILESTONE 4 · MEDIA TRANSCRIPTION</p>
            <h2>AI 转录任务</h2>
            <p>从 MinIO 获取视频，由 FFmpeg 提取音频，再通过 Mock ASR 生成时间戳字幕。</p>
          </div>
          <el-button
            class="analysis-start-button"
            :disabled="analysisActive || analysisComplete"
            :loading="startingAnalysis"
            @click="handleStartAnalysis"
          >
            {{ analysisActive ? '分析进行中' : analysisComplete ? '分析已完成' : '开始 AI 分析' }}
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
          {{
            analysisComplete
              ? '该视频已完成转录，字幕已从持久化数据加载。'
              : '尚未创建分析任务。点击按钮后，接口会立即返回任务编号并开始轮询状态。'
          }}
        </div>
      </div>

      <div class="transcript-panel content-panel">
        <div class="transcript-panel__heading">
          <div>
            <p class="eyebrow">TIMESTAMPED TRANSCRIPT</p>
            <h2>视频字幕</h2>
          </div>
          <span v-if="transcript.length" class="transcript-count">
            {{ transcript.length }} 个片段
          </span>
        </div>

        <div v-if="transcriptError" class="notice notice--error transcript-notice">
          {{ transcriptError }}
        </div>
        <div v-else-if="transcriptLoading" class="transcript-loading">
          <el-skeleton :rows="3" animated />
        </div>
        <ol v-else-if="transcript.length" class="transcript-list">
          <li v-for="segment in transcript" :key="`${segment.startMs}-${segment.endMs}`">
            <span class="transcript-timestamp">{{ formatTimestamp(segment.startMs) }}</span>
            <p>{{ segment.text }}</p>
          </li>
        </ol>
        <div v-else class="transcript-empty">
          尚未生成字幕。完成上方转录任务后，带时间戳的片段会显示在这里。
        </div>
      </div>
    </template>
  </section>
</template>
