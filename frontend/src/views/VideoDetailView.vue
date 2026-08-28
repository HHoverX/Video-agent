<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import AiContentPanel from '@/components/AiContentPanel.vue'
import AnalysisStatusCard from '@/components/AnalysisStatusCard.vue'
import TranscriptPanel from '@/components/TranscriptPanel.vue'
import VideoPlayer from '@/components/VideoPlayer.vue'
import VideoQaPanel from '@/components/VideoQaPanel.vue'
import { useAnalysisEvents } from '@/composables/useAnalysisEvents'
import { getAnalysisTask, getCurrentAnalysisTask, startAnalysis } from '@/services/analysis'
import { askAgenticQa, buildRagIndex, getRagStatus } from '@/services/rag'
import type { AgenticQaResponse, RagIndexStatusResponse } from '@/services/rag'
import { getVideoChapters, getVideoKeyPoints, getVideoSummary } from '@/services/summary'
import { getVideoTranscript } from '@/services/transcript'
import { apiErrorMessage, getVideo } from '@/services/video'
import type { AnalysisProgressEvent, AnalysisTask } from '@/types/analysis'
import { resolveAnalysisRecovery } from '@/utils/analysisRecovery'
import type { VideoChapter, VideoKeyPoint, VideoSummary } from '@/types/summary'
import type { TranscriptSegment } from '@/types/transcript'
import type { Video } from '@/types/video'

const POLL_INTERVAL_MILLIS = 1_000
const MAX_FALLBACK_POLLS = 180

const route = useRoute()
const playerRef = ref<InstanceType<typeof VideoPlayer> | null>(null)
const loading = ref(true)
const video = ref<Video | null>(null)
const errorMessage = ref('')
const startingAnalysis = ref(false)
const pollingAnalysis = ref(false)
const analysisTransport = ref<'idle' | 'sse' | 'polling'>('idle')
const analysisTask = ref<AnalysisTask | null>(null)
const analysisError = ref('')
const analysisTaskResolved = ref(false)
const transcript = ref<TranscriptSegment[]>([])
const transcriptLoading = ref(false)
const transcriptError = ref('')
const summary = ref<VideoSummary | null>(null)
const chapters = ref<VideoChapter[]>([])
const keyPoints = ref<VideoKeyPoint[]>([])
const summaryLoading = ref(false)
const summaryError = ref('')
const ragStatus = ref<RagIndexStatusResponse | null>(null)
const qaLoading = ref(false)
const qaError = ref('')
const qaResult = ref<AgenticQaResponse | null>(null)
const buildingIndex = ref(false)
let pollTimer: number | undefined
let fallbackPollCount = 0
let pageLoadVersion = 0

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

const analysisActive = computed(
  () => analysisTask.value?.status === 'PENDING'
    || analysisTask.value?.status === 'PROCESSING'
    || analysisTask.value?.status === 'RETRY_WAITING',
)
const analysisComplete = computed(
  () => analysisTask.value?.status === 'SUCCESS',
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

async function loadRagStatus() {
  const videoId = Number(route.params.id)
  if (!Number.isSafeInteger(videoId) || videoId <= 0) return
  try {
    ragStatus.value = await getRagStatus(videoId)
  } catch {
    // RAG status is auxiliary; a failure here should not block the page.
  }
}

async function handleBuildIndex() {
  const videoId = Number(route.params.id)
  buildingIndex.value = true
  qaError.value = ''
  try {
    ragStatus.value = await buildRagIndex(videoId)
  } catch (error) {
    qaError.value = apiErrorMessage(error, '索引构建失败。')
  } finally {
    buildingIndex.value = false
  }
}

async function handleAsk(question: string) {
  const normalizedQuestion = question.trim()
  if (!normalizedQuestion || qaLoading.value) return
  const videoId = Number(route.params.id)
  qaLoading.value = true
  qaError.value = ''
  qaResult.value = null
  try {
    qaResult.value = await askAgenticQa(videoId, normalizedQuestion)
  } catch (error) {
    qaError.value = apiErrorMessage(error, '问答请求失败。')
  } finally {
    qaLoading.value = false
  }
}

async function loadVideoMetadata(videoId: number, loadVersion: number): Promise<boolean> {
  try {
    const loadedVideo = await getVideo(videoId)
    if (loadVersion === pageLoadVersion && Number(route.params.id) === videoId) {
      video.value = loadedVideo
      return true
    }
    return false
  } catch (error) {
    if (loadVersion === pageLoadVersion && Number(route.params.id) === videoId) {
      errorMessage.value = apiErrorMessage(error, '视频详情加载失败。')
    }
    return false
  }
}

async function loadVideo() {
  const videoId = Number(route.params.id)
  const loadVersion = pageLoadVersion
  loading.value = true
  try {
    if (await loadVideoMetadata(videoId, loadVersion)) {
      await Promise.all([loadTranscript(), loadSummary(), loadRagStatus()])
    }
  } finally {
    loading.value = false
  }
}

function taskStorageKey(videoId = Number(route.params.id)) {
  return `videoagent:analysis-task:${videoId}`
}

function handleSeek(milliseconds: number): void {
  playerRef.value?.seekTo(milliseconds)
}

function rememberTask(taskId: number, videoId = Number(route.params.id)) {
  try {
    window.sessionStorage.setItem(taskStorageKey(videoId), String(taskId))
  } catch {
    // Session storage is optional; the active SSE connection still works without refresh recovery.
  }
}

function forgetTask(videoId = Number(route.params.id)) {
  try {
    window.sessionStorage.removeItem(taskStorageKey(videoId))
  } catch {
    // Ignore storage restrictions in privacy-focused browser contexts.
  }
}

function storedTaskId(videoId: number): number | null {
  try {
    const taskId = Number(window.sessionStorage.getItem(taskStorageKey(videoId)))
    return Number.isSafeInteger(taskId) && taskId > 0 ? taskId : null
  } catch {
    return null
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

function stopAnalysisTransport() {
  clearPollTimer()
  closeAnalysisEvents()
  analysisTransport.value = 'idle'
}

async function handleTerminalTask(task: AnalysisTask) {
  stopAnalysisTransport()
  if (task.status === 'SUCCESS') {
    forgetTask()
    await Promise.all([
      loadVideoMetadata(Number(route.params.id), pageLoadVersion),
      loadTranscript(),
      loadSummary(),
    ])
  } else {
    rememberTask(task.taskId)
  }
}

async function handleProgressEvent(event: AnalysisProgressEvent) {
  if (analysisTask.value?.taskId !== event.taskId) return
  const previous = analysisTask.value
  analysisTask.value = {
    ...event,
    createdAt: previous?.createdAt ?? new Date().toISOString(),
    startedAt: previous?.startedAt ?? (event.status === 'PENDING' ? null : new Date().toISOString()),
    finishedAt: event.status === 'SUCCESS' || event.status === 'FAILED'
      ? previous?.finishedAt ?? new Date().toISOString()
      : null,
  }
  analysisTaskResolved.value = true
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
    if (analysisTask.value?.taskId !== taskId) return
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
    if (analysisTask.value?.taskId !== taskId) return
    analysisError.value = apiErrorMessage(error, '分析状态加载失败，请稍后重试。')
    if (fallbackPollCount < MAX_FALLBACK_POLLS) schedulePoll(taskId)
  } finally {
    pollingAnalysis.value = false
  }
}

async function handleStartAnalysis() {
  if (!video.value || !analysisTaskResolved.value || startingAnalysis.value || analysisActive.value || analysisComplete.value) return

  startingAnalysis.value = true
  analysisError.value = ''
  try {
    const started = await startAnalysis(video.value.id)
    const retryWaiting = started.status === 'RETRY_WAITING'
    analysisTask.value = {
      ...started,
      stage: retryWaiting ? 'RETRY_WAITING' : 'QUEUED',
      progress: 0,
      message: retryWaiting ? '分析暂时失败，正在重试' : '任务已进入队列',
      errorCode: null,
      errorMessage: null,
      createdAt: new Date().toISOString(),
      startedAt: null,
      finishedAt: null,
    }
    analysisTaskResolved.value = true
    rememberTask(started.taskId)
    analysisTransport.value = 'sse'
    connectAnalysisEvents(started.taskId)
  } catch (error) {
    analysisError.value = apiErrorMessage(error, '无法发起分析，请稍后重试。')
  } finally {
    startingAnalysis.value = false
  }
}

async function recoverCurrentAnalysisTask(videoId: number, loadVersion: number) {
  const savedTaskId = storedTaskId(videoId)
  try {
    const current = await getCurrentAnalysisTask(videoId)
    if (loadVersion !== pageLoadVersion || Number(route.params.id) !== videoId) return

    const decision = resolveAnalysisRecovery(savedTaskId, current)
    stopAnalysisTransport()
    analysisTask.value = decision.task
    analysisTaskResolved.value = decision.resolved
    analysisError.value = ''
    if (decision.storageTaskId === null) {
      forgetTask(videoId)
    } else if (savedTaskId !== decision.storageTaskId) {
      rememberTask(decision.storageTaskId, videoId)
    }
    if (decision.shouldConnect && decision.task) {
      analysisTransport.value = 'sse'
      connectAnalysisEvents(decision.task.taskId)
    }
  } catch (error) {
    if (loadVersion !== pageLoadVersion || Number(route.params.id) !== videoId) return
    const decision = resolveAnalysisRecovery(savedTaskId, undefined)
    stopAnalysisTransport()
    analysisTask.value = decision.task
    analysisTaskResolved.value = decision.resolved
    analysisError.value = apiErrorMessage(error, '无法恢复当前分析任务。')
  }
}

async function loadPage() {
  const videoId = Number(route.params.id)
  const loadVersion = ++pageLoadVersion
  stopAnalysisTransport()
  analysisTask.value = null
  analysisTaskResolved.value = false
  analysisError.value = ''
  await loadVideo()
  if (loadVersion === pageLoadVersion && video.value?.id === videoId) {
    await recoverCurrentAnalysisTask(videoId, loadVersion)
  }
}

onMounted(loadPage)
watch(() => route.params.id, () => {
  void loadPage()
})
onBeforeUnmount(() => {
  pageLoadVersion += 1
  stopAnalysisTransport()
})
</script>

<template>
  <section class="page-section detail-page">
    <RouterLink v-if="loading || errorMessage" class="back-link" to="/">← 返回视频库</RouterLink>

    <div v-if="loading" class="content-panel loading-panel">
      <el-skeleton :rows="6" animated />
    </div>

    <div v-else-if="errorMessage" class="notice notice--error">{{ errorMessage }}</div>

    <template v-else-if="video">
      <div class="detail-header">
        <div class="detail-copy">
          <RouterLink class="back-link" to="/">← 返回视频库</RouterLink>
          <p class="eyebrow">视频详情</p>
          <h1>{{ video.title }}</h1>
          <p>{{ video.originalFilename }}</p>
          <span class="status-badge status-badge--large">视频已就绪</span>
        </div>
        <div class="detail-meta-grid">
          <div class="detail-meta-item">
            <span>文件大小</span>
            <strong>{{ formatBytes(video.fileSize) }}</strong>
          </div>
          <div class="detail-meta-item">
            <span>MIME 类型</span>
            <strong>{{ video.mimeType }}</strong>
          </div>
          <div class="detail-meta-item">
            <span>视频时长</span>
            <strong>{{ video.durationSeconds ? `${video.durationSeconds} 秒` : '待后续媒体阶段提取' }}</strong>
          </div>
          <div class="detail-meta-item">
            <span>上传时间</span>
            <strong>{{ formatDate(video.createdAt) }}</strong>
          </div>
        </div>
      </div>

      <div class="video-workspace">
        <AnalysisStatusCard
          class="video-workspace__analysis"
          :status="analysisTask?.status ?? null"
          :progress="analysisTask?.progress ?? 0"
          :message="analysisTask?.message ?? ''"
          :task-error="analysisTask?.errorMessage ?? null"
          :recovery-error="analysisError"
          :resolved="analysisTaskResolved"
          :starting="startingAnalysis"
          @start="handleStartAnalysis"
          @retry="handleStartAnalysis"
        />
        <VideoPlayer
          ref="playerRef"
          class="detail-visual video-workspace__player"
          :video-id="video.id"
        />
        <AiContentPanel
          class="video-workspace__content"
          :summary="summary"
          :chapters="chapters"
          :key-points="keyPoints"
          :loading="summaryLoading"
          :error="summaryError"
          @seek="handleSeek"
        />
        <VideoQaPanel
          :key="video.id"
          class="video-workspace__qa"
          :rag-status="ragStatus"
          :loading="qaLoading"
          :building="buildingIndex"
          :error="qaError"
          :result="qaResult"
          @ask="handleAsk"
          @prepare="handleBuildIndex"
          @seek="handleSeek"
        />
        <TranscriptPanel
          class="video-workspace__transcript"
          :segments="transcript"
          :loading="transcriptLoading"
          :error="transcriptError"
          @seek="handleSeek"
        />
      </div>
    </template>
  </section>
</template>
