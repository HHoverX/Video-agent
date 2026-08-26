<script setup lang="ts">
import { computed } from 'vue'

import type { AnalysisStatus } from '@/types/analysis'

const props = defineProps<{
  status: AnalysisStatus | null
  progress: number
  message: string
  taskError: string | null
  recoveryError: string
  resolved: boolean
  starting: boolean
}>()

const emit = defineEmits<{
  start: []
  retry: []
}>()

const statusLabel = computed(() => {
  const labels: Record<AnalysisStatus, string> = {
    PENDING: '等待分析',
    PROCESSING: '分析中',
    RETRY_WAITING: '等待重试',
    SUCCESS: '分析已完成',
    FAILED: '分析失败',
  }
  return props.status ? labels[props.status] : '尚未开始'
})

const progressStatus = computed(() => {
  if (props.status === 'SUCCESS') return 'success'
  if (props.status === 'FAILED') return 'exception'
  return undefined
})

const active = computed(
  () => props.status === 'PENDING' || props.status === 'PROCESSING' || props.status === 'RETRY_WAITING',
)
const completed = computed(() => props.status === 'SUCCESS')
const actionDisabled = computed(() => !props.resolved || props.starting || active.value || completed.value)
const actionLabel = computed(() => {
  if (!props.resolved) return '正在恢复分析状态'
  if (active.value) return props.status === 'RETRY_WAITING' ? '等待重试' : '分析中'
  if (completed.value) return '分析已完成'
  return props.status === 'FAILED' ? '重试分析' : '开始 AI 分析'
})

function requestAnalysis() {
  if (props.status === 'FAILED') {
    emit('retry')
  } else {
    emit('start')
  }
}
</script>

<template>
  <section class="analysis-panel content-panel">
    <div class="analysis-panel__heading">
      <div>
        <h2>AI 分析</h2>
        <p>生成转录文本、章节与关键内容。</p>
      </div>
      <el-button
        class="analysis-start-button"
        :disabled="actionDisabled"
        :loading="starting"
        @click="requestAnalysis"
      >
        {{ actionLabel }}
      </el-button>
    </div>

    <div v-if="recoveryError" class="notice notice--error analysis-notice">
      {{ recoveryError }}
    </div>

    <div v-if="status" class="analysis-progress" aria-live="polite">
      <div class="analysis-progress__meta">
        <strong>{{ statusLabel }}</strong>
        <span>{{ message }}</span>
      </div>
      <el-progress
        :percentage="progress"
        :status="progressStatus"
        :stroke-width="10"
      />
      <p v-if="status === 'FAILED'" class="analysis-failure">
        {{ taskError || '任务处理失败。' }}
      </p>
      <p v-else-if="status === 'RETRY_WAITING'" class="analysis-polling">分析暂时失败，等待重试…</p>
    </div>

    <div v-else class="analysis-empty">
      {{ !resolved ? '正在恢复当前分析状态。' : '尚未开始分析。' }}
    </div>
  </section>
</template>
