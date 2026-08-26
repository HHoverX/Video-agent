<script setup lang="ts">
import type { UploadFile, UploadFiles } from 'element-plus'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'

import { apiErrorMessage } from '@/services/video'
import {
  cancelUpload,
  completeUpload,
  confirmUploadPart,
  createPartUploadUrl,
  createUploadSession,
  getUploadSession,
  uploadPartDirect,
} from '@/services/upload'
import type { UploadSession } from '@/types/upload'

const MAX_FILE_SIZE = 20 * 1024 * 1024 * 1024
const MAX_PART_RETRIES = 3

const router = useRouter()
const selectedFile = ref<File | null>(null)
const title = ref('')
const session = ref<UploadSession | null>(null)
const uploading = ref(false)
const paused = ref(false)
const progress = ref(0)
const validationMessage = ref('')
const statusMessage = ref('')

const activeControllers = new Map<number, AbortController>()
const activeLoaded = new Map<number, number>()
const completedBytes = new Map<number, number>()

const selectedFileSize = computed(() => {
  if (!selectedFile.value) return ''
  const gib = selectedFile.value.size / 1024 / 1024 / 1024
  return gib >= 1 ? `${gib.toFixed(2)} GB` : `${(selectedFile.value.size / 1024 / 1024).toFixed(2)} MB`
})

const submitLabel = computed(() => {
  if (uploading.value) return '正在分片上传…'
  if (paused.value || session.value) return '继续上传缺失分片'
  return '开始分片上传'
})

function storageKey(file: File): string {
  return `videoagent:upload:${file.name}:${file.size}:${file.lastModified}`
}

function rememberAnalysisTask(videoId: number, taskId: number) {
  try {
    window.sessionStorage.setItem(`videoagent:analysis-task:${videoId}`, String(taskId))
  } catch {
    // Detail recovery is auxiliary; upload completion remains successful without session storage.
  }
}

async function handleFileChange(uploadFile: UploadFile, uploadFiles: UploadFiles) {
  const rawFile = uploadFile.raw
  validationMessage.value = ''
  statusMessage.value = ''
  selectedFile.value = null
  session.value = null
  progress.value = 0
  completedBytes.clear()

  if (!rawFile) return
  const hasMp4Extension = rawFile.name.toLowerCase().endsWith('.mp4')
  const hasSupportedType = !rawFile.type || rawFile.type === 'video/mp4' || rawFile.type === 'application/mp4'
  if (!hasMp4Extension || !hasSupportedType) {
    validationMessage.value = '请选择 MP4 格式的视频文件。'
    uploadFiles.splice(0, uploadFiles.length)
    return
  }
  if (rawFile.size > MAX_FILE_SIZE) {
    validationMessage.value = '视频文件不能超过 20 GB。'
    uploadFiles.splice(0, uploadFiles.length)
    return
  }

  selectedFile.value = rawFile
  if (!title.value.trim()) title.value = rawFile.name.replace(/\.mp4$/i, '')
  await restoreSession(rawFile)
}

async function restoreSession(file: File) {
  const uploadId = localStorage.getItem(storageKey(file))
  if (!uploadId) return
  try {
    const restored = await getUploadSession(uploadId)
    if (restored.fileName !== file.name || restored.fileSize !== file.size) {
      localStorage.removeItem(storageKey(file))
      return
    }
    if (restored.status === 'CANCELLED' || restored.status === 'EXPIRED') {
      localStorage.removeItem(storageKey(file))
      return
    }
    session.value = restored
    title.value = restored.title
    setCompletedParts(restored)
    updateProgress()
    if (restored.status === 'COMPLETED' && restored.videoId) {
      statusMessage.value = '该文件已经上传完成。'
    } else {
      paused.value = true
      statusMessage.value = `已恢复上传会话，只需补传 ${restored.totalParts - restored.completedParts.length} 个分片。`
    }
  } catch {
    localStorage.removeItem(storageKey(file))
  }
}

function handleRemove() {
  pauseUpload()
  selectedFile.value = null
  session.value = null
  progress.value = 0
  statusMessage.value = ''
  completedBytes.clear()
}

async function submitUpload() {
  const file = selectedFile.value
  if (!file) {
    validationMessage.value = '请先选择一个 MP4 视频。'
    return
  }
  if (!title.value.trim() || title.value.trim().length > 255) {
    validationMessage.value = '视频标题长度必须为 1 至 255 个字符。'
    return
  }
  if (session.value?.status === 'COMPLETED' && session.value.videoId) {
    if (session.value.analysisTaskId) {
      rememberAnalysisTask(session.value.videoId, session.value.analysisTaskId)
    }
    await router.push(`/videos/${session.value.videoId}`)
    return
  }

  uploading.value = true
  paused.value = false
  validationMessage.value = ''
  statusMessage.value = '正在准备上传会话…'
  try {
    if (!session.value) {
      const created = await createUploadSession({
        fileName: file.name,
        title: title.value.trim(),
        fileSize: file.size,
        contentType: file.type || 'video/mp4',
      })
      session.value = created
      localStorage.setItem(storageKey(file), created.uploadId)
    } else {
      session.value = await getUploadSession(session.value.uploadId)
    }
    setCompletedParts(session.value)
    await uploadMissingParts(file, session.value)
    if (paused.value) return

    statusMessage.value = '全部分片已上传，正在服务端合并并创建分析任务…'
    const completed = await completeUpload(session.value.uploadId)
    localStorage.removeItem(storageKey(file))
    progress.value = 100
    rememberAnalysisTask(completed.videoId, completed.analysisTaskId)
    ElMessage.success('视频上传完成，分析任务已进入队列')
    await router.push(`/videos/${completed.videoId}`)
  } catch (error) {
    if (paused.value) {
      statusMessage.value = '上传已暂停，已完成分片已保存。'
    } else {
      validationMessage.value = apiErrorMessage(error, '上传暂时失败，可点击继续，仅重传缺失分片。')
      statusMessage.value = '上传会话和已完成分片已保留。'
    }
  } finally {
    uploading.value = false
    activeControllers.clear()
    activeLoaded.clear()
    updateProgress()
  }
}

async function uploadMissingParts(file: File, current: UploadSession) {
  const done = new Set(current.completedParts.map((part) => part.partNumber))
  const missing = Array.from({ length: current.totalParts }, (_, index) => index + 1)
    .filter((partNumber) => !done.has(partNumber))
  if (missing.length === 0) return

  statusMessage.value = `正在上传 ${missing.length} 个缺失分片，并发数 ${current.maxConcurrency}。`
  let cursor = 0
  async function worker() {
    while (!paused.value) {
      const index = cursor++
      if (index >= missing.length) return
      await uploadOnePart(file, current, missing[index])
    }
  }
  const concurrency = Math.max(1, Math.min(current.maxConcurrency, missing.length))
  await Promise.all(Array.from({ length: concurrency }, () => worker()))
}

async function uploadOnePart(file: File, current: UploadSession, partNumber: number) {
  const start = (partNumber - 1) * current.chunkSize
  const end = Math.min(start + current.chunkSize, file.size)
  const blob = file.slice(start, end)

  for (let attempt = 1; attempt <= MAX_PART_RETRIES; attempt++) {
    if (paused.value) throw new Error('upload paused')
    const controller = new AbortController()
    activeControllers.set(partNumber, controller)
    try {
      const signed = await createPartUploadUrl(current.uploadId, partNumber)
      if (!signed.alreadyCompleted) {
        if (!signed.uploadUrl) throw new Error('missing presigned upload URL')
        await uploadPartDirect(signed.uploadUrl, blob, controller.signal, (loaded) => {
          activeLoaded.set(partNumber, loaded)
          updateProgress()
        })
        await confirmUploadPart(current.uploadId, partNumber)
      }
      completedBytes.set(partNumber, blob.size)
      activeLoaded.delete(partNumber)
      activeControllers.delete(partNumber)
      updateProgress()
      return
    } catch (error) {
      activeLoaded.delete(partNumber)
      activeControllers.delete(partNumber)
      updateProgress()
      if (paused.value || controller.signal.aborted || attempt === MAX_PART_RETRIES) throw error
      const delay = Math.min(8_000, 500 * 2 ** (attempt - 1)) + Math.floor(Math.random() * 500)
      await new Promise((resolve) => window.setTimeout(resolve, delay))
    }
  }
}

function pauseUpload() {
  if (!uploading.value) return
  paused.value = true
  for (const controller of activeControllers.values()) controller.abort()
  statusMessage.value = '正在暂停；服务端已记录的分片不会丢失。'
}

async function cancelCurrentUpload() {
  const file = selectedFile.value
  const current = session.value
  if (!file || !current || current.status === 'COMPLETED') return
  await ElMessageBox.confirm('取消后服务端会清理临时分片，确定取消吗？', '取消上传', { type: 'warning' })
  pauseUpload()
  await cancelUpload(current.uploadId)
  localStorage.removeItem(storageKey(file))
  session.value = null
  completedBytes.clear()
  progress.value = 0
  statusMessage.value = '上传已取消，临时分片将被清理。'
}

function setCompletedParts(current: UploadSession) {
  completedBytes.clear()
  for (const part of current.completedParts) completedBytes.set(part.partNumber, part.size)
}

function updateProgress() {
  const fileSize = selectedFile.value?.size ?? 0
  if (!fileSize) {
    progress.value = 0
    return
  }
  const completed = Array.from(completedBytes.values()).reduce((sum, value) => sum + value, 0)
  const active = Array.from(activeLoaded.entries())
    .filter(([partNumber]) => !completedBytes.has(partNumber))
    .reduce((sum, [, value]) => sum + value, 0)
  progress.value = Math.min(99, Math.floor(((completed + active) * 100) / fileSize))
}
</script>

<template>
  <section class="page-section upload-page">
    <div class="page-heading page-heading--compact">
      <div>
        <p class="eyebrow">RESUMABLE UPLOAD</p>
        <h1>上传长视频</h1>
        <p class="page-description">分片直传 MinIO；刷新或网络中断后重新选择同一文件即可补传缺失分片。</p>
      </div>
    </div>

    <div class="upload-layout">
      <div class="content-panel upload-panel">
        <label class="field-label" for="video-title">视频标题</label>
        <el-input
          id="video-title"
          v-model="title"
          maxlength="255"
          show-word-limit
          placeholder="默认使用文件名"
          :disabled="uploading || !!session"
        />

        <label class="field-label">MP4 文件</label>
        <el-upload
          class="video-uploader"
          drag
          action="#"
          accept=".mp4,video/mp4"
          :auto-upload="false"
          :limit="1"
          :disabled="uploading"
          :on-change="handleFileChange"
          :on-remove="handleRemove"
        >
          <div class="upload-symbol">＋</div>
          <div class="el-upload__text">拖拽 MP4 到这里，或 <em>点击选择</em></div>
          <template #tip>
            <div class="el-upload__tip">仅支持 MP4 · 最大 20 GB · 默认 16 MB/片</div>
          </template>
        </el-upload>

        <div v-if="selectedFile" class="selected-file">
          <span>{{ selectedFile.name }}</span>
          <strong>{{ selectedFileSize }}</strong>
        </div>

        <el-progress
          v-if="uploading || progress > 0"
          class="upload-progress"
          :percentage="progress"
          :stroke-width="8"
        />

        <p v-if="statusMessage" class="page-description">{{ statusMessage }}</p>
        <p v-if="validationMessage" class="field-error">{{ validationMessage }}</p>

        <div class="upload-actions">
          <el-button
            class="submit-button"
            type="primary"
            size="large"
            :disabled="!selectedFile || uploading"
            @click="submitUpload"
          >
            {{ submitLabel }}
          </el-button>
          <el-button v-if="uploading" size="large" @click="pauseUpload">暂停</el-button>
          <el-button
            v-if="session && session.status !== 'COMPLETED'"
            size="large"
            type="danger"
            plain
            :disabled="uploading && !paused"
            @click="cancelCurrentUpload"
          >取消并清理</el-button>
        </div>
      </div>

      <aside class="upload-note">
        <span class="note-index">RELIABLE</span>
        <h2>长视频上传链路</h2>
        <ol>
          <li><span>01</span> MySQL 持久化上传会话</li>
          <li><span>02</span> 浏览器限并发直传临时分片</li>
          <li><span>03</span> 查询会话后只补缺失分片</li>
          <li><span>04</span> MinIO 服务端合并并异步分析</li>
        </ol>
      </aside>
    </div>
  </section>
</template>
