<script setup lang="ts">
import type { UploadFile, UploadFiles } from 'element-plus'
import { ElMessage } from 'element-plus'
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'

import { apiErrorMessage, uploadVideo } from '@/services/video'

const MAX_FILE_SIZE = 500 * 1024 * 1024

const router = useRouter()
const selectedFile = ref<File | null>(null)
const title = ref('')
const uploading = ref(false)
const progress = ref(0)
const validationMessage = ref('')

const selectedFileSize = computed(() => {
  if (!selectedFile.value) return ''
  return `${(selectedFile.value.size / 1024 / 1024).toFixed(2)} MB`
})

function handleFileChange(uploadFile: UploadFile, uploadFiles: UploadFiles) {
  const rawFile = uploadFile.raw
  validationMessage.value = ''
  selectedFile.value = null

  if (!rawFile) return
  const hasMp4Extension = rawFile.name.toLowerCase().endsWith('.mp4')
  const hasSupportedType = !rawFile.type || rawFile.type === 'video/mp4' || rawFile.type === 'application/mp4'
  if (!hasMp4Extension || !hasSupportedType) {
    validationMessage.value = '请选择 MP4 格式的视频文件。'
    uploadFiles.splice(0, uploadFiles.length)
    return
  }
  if (rawFile.size > MAX_FILE_SIZE) {
    validationMessage.value = '视频文件不能超过 500 MB。'
    uploadFiles.splice(0, uploadFiles.length)
    return
  }

  selectedFile.value = rawFile
  if (!title.value.trim()) {
    title.value = rawFile.name.replace(/\.mp4$/i, '')
  }
}

function handleRemove() {
  selectedFile.value = null
  progress.value = 0
}

async function submitUpload() {
  if (!selectedFile.value) {
    validationMessage.value = '请先选择一个 MP4 视频。'
    return
  }
  if (!title.value.trim() || title.value.trim().length > 255) {
    validationMessage.value = '视频标题长度必须为 1 至 255 个字符。'
    return
  }

  uploading.value = true
  progress.value = 0
  validationMessage.value = ''
  try {
    const response = await uploadVideo(selectedFile.value, title.value, (value) => {
      progress.value = value
    })
    progress.value = 100
    ElMessage.success('视频上传成功')
    await router.push(`/videos/${response.videoId}`)
  } catch (error) {
    validationMessage.value = apiErrorMessage(error, '上传失败，请稍后重试。')
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <section class="page-section upload-page">
    <div class="page-heading page-heading--compact">
      <div>
        <p class="eyebrow">UPLOAD</p>
        <h1>上传视频</h1>
        <p class="page-description">当前阶段支持普通 multipart MP4 上传，单文件最大 500 MB。</p>
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
          :disabled="uploading"
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
            <div class="el-upload__tip">仅支持 MP4 · 最大 500 MB</div>
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

        <p v-if="validationMessage" class="field-error">{{ validationMessage }}</p>

        <el-button
          class="submit-button"
          type="primary"
          size="large"
          :loading="uploading"
          :disabled="!selectedFile"
          @click="submitUpload"
        >
          {{ uploading ? '正在上传…' : '上传到 VideoAgent' }}
        </el-button>
      </div>

      <aside class="upload-note">
        <span class="note-index">M2</span>
        <h2>本阶段的数据链路</h2>
        <ol>
          <li><span>01</span> 浏览器发送 multipart 文件</li>
          <li><span>02</span> 后端校验 MP4 类型和大小</li>
          <li><span>03</span> 视频对象写入 MinIO</li>
          <li><span>04</span> 元数据写入 MySQL</li>
        </ol>
      </aside>
    </div>
  </section>
</template>
