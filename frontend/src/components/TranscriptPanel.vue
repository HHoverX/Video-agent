<script setup lang="ts">
import { formatTimestamp } from '@/utils/formatTimestamp'
import type { TranscriptSegment } from '@/types/transcript'

defineProps<{
  segments: TranscriptSegment[]
  loading: boolean
  error: string
}>()
</script>

<template>
  <section class="transcript-panel content-panel">
    <div class="transcript-panel__heading">
      <div>
        <h2>转录文本</h2>
      </div>
      <span v-if="segments.length" class="transcript-count">{{ segments.length }} 个片段</span>
    </div>

    <div v-if="error" class="notice notice--error transcript-notice">{{ error }}</div>
    <div v-else-if="loading" class="transcript-loading">
      <el-skeleton :rows="3" animated />
    </div>
    <div v-else-if="segments.length" class="transcript-reading-area">
      <ol class="transcript-list">
        <li v-for="segment in segments" :key="`${segment.startMs}-${segment.endMs}`">
          <span class="transcript-timestamp">{{ formatTimestamp(segment.startMs) }}</span>
          <p>{{ segment.text }}</p>
        </li>
      </ol>
    </div>
    <div v-else class="transcript-empty">
      尚未生成转录文本。完成分析后，带时间戳的内容会显示在这里。
    </div>
  </section>
</template>
