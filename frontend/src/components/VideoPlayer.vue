<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'

import { apiErrorMessage, getVideoPlaybackUrl } from '@/services/video'

const props = defineProps<{
  videoId: number
}>()

const videoElement = ref<HTMLVideoElement | null>(null)
const playbackUrl = ref('')
const loading = ref(false)
const requesting = ref(false)
const errorMessage = ref('')
let pendingSeekSeconds: number | null = null
let activeRequest = 0

function clearMedia() {
  const element = videoElement.value
  playbackUrl.value = ''
  if (element) {
    element.removeAttribute('src')
    element.load()
  }
}

async function loadPlaybackUrl() {
  if (requesting.value) return

  const requestId = ++activeRequest
  requesting.value = true
  loading.value = true
  errorMessage.value = ''
  clearMedia()

  try {
    const response = await getVideoPlaybackUrl(props.videoId)
    if (requestId !== activeRequest) return

    playbackUrl.value = response.url
    await nextTick()
    if (requestId === activeRequest) videoElement.value?.load()
  } catch (error) {
    if (requestId !== activeRequest) return
    loading.value = false
    errorMessage.value = apiErrorMessage(error, '播放地址加载失败，请重试。')
  } finally {
    if (requestId === activeRequest) requesting.value = false
  }
}

function applySeek(seconds: number) {
  const element = videoElement.value
  if (!element || element.readyState < HTMLMediaElement.HAVE_METADATA) return false

  const duration = element.duration
  const target = Number.isFinite(duration)
    ? Math.min(seconds, Math.max(0, duration))
    : seconds
  try {
    element.currentTime = target
    return true
  } catch {
    return false
  }
}

function seekTo(milliseconds: number): void {
  if (!Number.isFinite(milliseconds)) return

  const seconds = Math.max(0, milliseconds) / 1_000
  if (applySeek(seconds)) {
    pendingSeekSeconds = null
  } else {
    pendingSeekSeconds = seconds
  }
}

function handleLoadedMetadata() {
  loading.value = false
  errorMessage.value = ''
  if (pendingSeekSeconds !== null && applySeek(pendingSeekSeconds)) {
    pendingSeekSeconds = null
  }
}

function handleMediaError() {
  if (!playbackUrl.value) return
  loading.value = false
  errorMessage.value = '视频加载失败或播放地址已失效。'
}

function retry() {
  if (!requesting.value) void loadPlaybackUrl()
}

defineExpose({ seekTo })

onMounted(() => void loadPlaybackUrl())
onBeforeUnmount(() => {
  activeRequest += 1
  clearMedia()
})
</script>

<template>
  <div class="video-player">
    <video
      v-if="playbackUrl"
      ref="videoElement"
      class="video-player__media"
      :src="playbackUrl"
      controls
      playsinline
      preload="metadata"
      @loadedmetadata="handleLoadedMetadata"
      @error="handleMediaError"
    />

    <div v-if="loading" class="video-player__state" role="status">
      <span class="video-player__spinner" aria-hidden="true"></span>
      <p>正在加载播放器…</p>
    </div>

    <div v-else-if="errorMessage" class="video-player__state video-player__state--error" role="alert">
      <p>{{ errorMessage }}</p>
      <button class="video-player__retry" type="button" :disabled="requesting" @click="retry">
        重试
      </button>
    </div>
  </div>
</template>
