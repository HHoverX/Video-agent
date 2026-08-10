<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import { useAnalysisEvents } from '@/composables/useAnalysisEvents'
import { getAnalysisTask, startAnalysis } from '@/services/analysis'
import { getVideoChapters, getVideoKeyPoints, getVideoSummary } from '@/services/summary'
import { getVideoTranscript } from '@/services/transcript'
import { apiErrorMessage, getVideo } from '@/services/video'
import type { AnalysisProgressEvent, AnalysisTask, AnalysisStatus } from '@/types/analysis'
import type { VideoChapter, VideoKeyPoint, VideoSummary } from '@/types/summary'
import type { TranscriptSegment } from '@/types/transcript'
import type { Video } from '@/types/video'

const POLL_INTERVAL_MILLIS = 1_000
const MAX_FALLBACK_POLLS = 180

const route = useRoute()
const loading = ref(true)
const video = ref<Video | null>(null)
const errorMessage = ref('')
const startingAnalysis = ref(false)
const pollingAnalysis = ref(false)
const analysisTransport = ref<'idle' | 'sse' | 'polling'>('idle')
const analysisTask = ref<AnalysisTask | null>(null)
const analysisError = ref('')
const transcript = ref<TranscriptSegment[]>([])
const transcriptLoading = ref(false)
const transcriptError = ref('')
const summary = ref<VideoSummary | null>(null)
const chapters = ref<VideoChapter[]>([])
const keyPoints = ref<VideoKeyPoint[]>([])
const summaryLoading = ref(false)
const summaryError = ref('')
let pollTimer: number | undefined
let fallbackPollCount = 0

const { connect: connectAnalysisEvents, close: closeAnalysisEvents } = useAnalysisEvents({
  onOpen: () => {
    analysisTransport.value = 'sse'
  },
  onProgress: (event) => {
    void handleProgressEvent(event)
  },
  onError: (taskId) => {
    if (analysisActive.value) startPollingFallback(taskId)
  },
})

const analysisStatusLabel = computed(() => {
  const labels: Record<AnalysisStatus, string> = {
    PENDING: '排队中',
    PROCESSING: '处理中',
    RETRY_WAITING: '重试中',
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
  () => analysisTask.value?.status === 'PENDING'
    || analysisTask.value?.status === 'PROCESSING'
    || analysisTask.value?.status === 'RETRY_WAITING',
)
const analysisComplete = computed(
  () => analysisTask.value?.status === 'SUCCESS' || summary.value !== null,
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

async function loadSummary() {
  const videoId = Number(route.params.id)
  summaryLoading.value = true
  try {
    const [loadedSummary, loadedChapters, loadedKeyPoints] = await Promise.all([
      getVideoSummary(videoId),
      getVideoChapters(videoId),
      getVideoKeyPoints(videoId),
    ])
    summary.value = loadedSummary
    chapters.value = loadedChapters
    keyPoints.value = loadedKeyPoints
    summaryError.value = ''
  } catch (error) {
    summaryError.value = apiErrorMessage(error, 'AI 总结加载失败，请稍后重试。')
  } finally {
    summaryLoading.value = false
  }
}

async function loadVideo() {
  loading.value = true
  try {
    video.value = await getVideo(Number(route.params.id))
    await Promise.all([loadTranscript(), loadSummary()])
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, '视频详情加载失败。')
  } finally {
    loading.value = false
  }
}

function taskStorageKey() {
  return `videoagent:analysis-task:${Number(route.params.id)}`
}

function rememberTask(taskId: number) {
  try {
    window.sessionStorage.setItem(taskStorageKey(), String(taskId))
  } catch {
    // Session storage is optional; the active SSE connection still works without refresh recovery.
  }
}

function forgetTask() {
  try {
    window.sessionStorage.removeItem(taskStorageKey())
  } catch {
    // Ignore storage restrictions in privacy-focused browser contexts.
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

function startPollingFallback(taskId: number) {
  closeAnalysisEvents()
  analysisTransport.value = 'polling'
  fallbackPollCount = 0
  schedulePoll(taskId)
}

async function handleTerminalTask(task: AnalysisTask) {
  clearPollTimer()
  closeAnalysisEvents()
  analysisTransport.value = 'idle'
  forgetTask()
  if (task.status === 'SUCCESS') {
    await Promise.all([loadTranscript(), loadSummary()])
  }
}

async function handleProgressEvent(event: AnalysisProgressEvent) {
  const previous = analysisTask.value
  analysisTask.value = {
    ...event,
    createdAt: previous?.createdAt ?? new Date().toISOString(),
    startedAt: previous?.startedAt ?? (event.status === 'PENDING' ? null : new Date().toISOString()),
    finishedAt: event.status === 'SUCCESS' || event.status === 'FAILED'
      ? previous?.finishedAt ?? new Date().toISOString()
      : null,
  }
  analysisError.value = ''
  if (event.status === 'SUCCESS' || event.status === 'FAILED') {
    await handleTerminalTask(analysisTask.value)
  }
}

async function pollAnalysis(taskId: number) {
  pollingAnalysis.value = true
  fallbackPollCount += 1
  try {
    const current = await getAnalysisTask(taskId)
    analysisTask.value = current
    analysisError.value = ''
    if (current.status === 'SUCCESS' || current.status === 'FAILED') {
      await handleTerminalTask(current)
    } else if (analysisActive.value) {
      if (fallbackPollCount < MAX_FALLBACK_POLLS) {
        schedulePoll(taskId)
      } else {
        analysisTransport.value = 'idle'
        analysisError.value = '实时连接不可用，轮询已达到上限。任务仍在后台运行，可刷新页面恢复状态。'
      }
    }
  } catch (error) {
    analysisError.value = apiErrorMessage(error, '分析状态加载失败，请稍后重试。')
    if (fallbackPollCount < MAX_FALLBACK_POLLS) schedulePoll(taskId)
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
    rememberTask(started.taskId)
    analysisTransport.value = 'sse'
    connectAnalysisEvents(started.taskId)
  } catch (error) {
    analysisError.value = apiErrorMessage(error, '无法发起分析，请稍后重试。')
  } finally {
    startingAnalysis.value = false
  }
}

async function recoverAnalysisTask() {
  let storedTaskId = 0
  try {
    storedTaskId = Number(window.sessionStorage.getItem(taskStorageKey()))
  } catch {
    return
  }
  if (!Number.isSafeInteger(storedTaskId) || storedTaskId <= 0) return

  try {
    const current = await getAnalysisTask(storedTaskId)
    analysisTask.value = current
    if (current.status === 'PENDING' || current.status === 'PROCESSING' || current.status === 'RETRY_WAITING') {
      analysisTransport.value = 'sse'
      connectAnalysisEvents(storedTaskId)
    } else {
      await handleTerminalTask(current)
    }
  } catch (error) {
    forgetTask()
    analysisError.value = apiErrorMessage(error, '无法恢复上次分析任务。')
  }
}

async function loadPage() {
  await loadVideo()
  if (video.value) await recoverAnalysisTask()
}

onMounted(loadPage)
onBeforeUnmount(() => {
  clearPollTimer()
  closeAnalysisEvents()
})
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
            <p class="eyebrow">MILESTONE 6 · SSE REAL-TIME PROGRESS</p>
            <h2>AI 视频分析</h2>
            <p>通过 SSE 实时展示音频提取、时间戳转录和结构化总结进度，GET 查询作为断线兜底。</p>
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
          <p v-else-if="analysisTask.status === 'RETRY_WAITING'" class="analysis-polling">
            分析暂时失败，正在重试…
          </p>
          <p v-else-if="analysisTransport === 'sse'" class="analysis-polling">SSE 实时进度已连接</p>
          <p v-else-if="analysisTransport === 'polling' || pollingAnalysis" class="analysis-polling">
            SSE 不可用，正在使用 GET 查询兜底…
          </p>
        </div>

        <div v-else class="analysis-empty">
          {{
            analysisComplete
              ? '该视频已完成结构化分析，结果已从持久化数据加载。'
              : '尚未创建分析任务。点击按钮后，接口会立即返回任务编号并建立 SSE 实时连接。'
          }}
        </div>
      </div>

      <div class="summary-panel content-panel">
        <div class="summary-panel__heading">
          <div>
            <p class="eyebrow">AI SUMMARY</p>
            <h2>结构化视频总结</h2>
          </div>
          <span v-if="summary" class="summary-task-label">任务 #{{ summary.taskId }}</span>
        </div>

        <div v-if="summaryError" class="notice notice--error summary-notice">
          {{ summaryError }}
        </div>
        <div v-else-if="summaryLoading" class="summary-loading">
          <el-skeleton :rows="6" animated />
        </div>
        <div v-else-if="summary" class="summary-content">
          <section class="summary-overview">
            <h3>Overview</h3>
            <p>{{ summary.overview }}</p>
          </section>

          <section class="summary-section">
            <div class="summary-section__title">
              <h3>Chapters</h3>
              <span>{{ chapters.length }} 章</span>
            </div>
            <ol class="chapter-list">
              <li v-for="chapter in chapters" :key="chapter.chapterIndex">
                <span class="summary-timestamp">
                  {{ formatTimestamp(chapter.startMs) }} – {{ formatTimestamp(chapter.endMs) }}
                </span>
                <div>
                  <h4>{{ chapter.title }}</h4>
                  <p>{{ chapter.summary }}</p>
                </div>
              </li>
            </ol>
          </section>

          <section class="summary-section">
            <div class="summary-section__title">
              <h3>Key Points</h3>
              <span>{{ keyPoints.length }} 条</span>
            </div>
            <ul class="key-point-list">
              <li v-for="point in keyPoints" :key="point.pointIndex">
                <span class="summary-timestamp">
                  {{ formatTimestamp(point.startMs) }} – {{ formatTimestamp(point.endMs) }}
                </span>
                <p>{{ point.content }}</p>
              </li>
            </ul>
          </section>
        </div>
        <div v-else class="summary-empty">
          尚未生成结构化总结。启动上方分析后，Overview、Chapters 与 Key Points 会显示在这里。
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
