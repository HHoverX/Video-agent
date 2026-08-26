<script setup lang="ts">
import { formatTimestamp } from '@/utils/formatTimestamp'
import type { VideoChapter, VideoKeyPoint, VideoSummary } from '@/types/summary'

defineProps<{
  summary: VideoSummary | null
  chapters: VideoChapter[]
  keyPoints: VideoKeyPoint[]
  loading: boolean
  error: string
}>()

const emit = defineEmits<{
  seek: [milliseconds: number]
}>()
</script>

<template>
  <section class="summary-panel content-panel">
    <div class="summary-panel__heading">
      <h2>AI 内容</h2>
    </div>

    <div v-if="error" class="notice notice--error summary-notice">{{ error }}</div>
    <div v-else-if="loading" class="summary-loading">
      <el-skeleton :rows="6" animated />
    </div>
    <div v-else-if="summary" class="summary-content">
      <section class="summary-overview">
        <h3>内容概览</h3>
        <p>{{ summary.overview }}</p>
      </section>

      <section class="summary-section">
        <div class="summary-section__title">
          <h3>章节</h3>
          <span>{{ chapters.length }} 章</span>
        </div>
        <ol class="chapter-list">
          <li v-for="chapter in chapters" :key="chapter.chapterIndex">
            <button
              class="summary-timestamp timestamp-seek"
              type="button"
              :aria-label="`跳转到章节 ${formatTimestamp(chapter.startMs)}`"
              @click="emit('seek', chapter.startMs)"
            >
              {{ formatTimestamp(chapter.startMs) }} – {{ formatTimestamp(chapter.endMs) }}
            </button>
            <div>
              <h4>{{ chapter.title }}</h4>
              <p>{{ chapter.summary }}</p>
            </div>
          </li>
        </ol>
      </section>

      <section class="summary-section">
        <div class="summary-section__title">
          <h3>关键内容</h3>
          <span>{{ keyPoints.length }} 条</span>
        </div>
        <ul class="key-point-list">
          <li v-for="point in keyPoints" :key="point.pointIndex">
            <button
              class="summary-timestamp timestamp-seek"
              type="button"
              :aria-label="`跳转到关键内容 ${formatTimestamp(point.startMs)}`"
              @click="emit('seek', point.startMs)"
            >
              {{ formatTimestamp(point.startMs) }} – {{ formatTimestamp(point.endMs) }}
            </button>
            <p>{{ point.content }}</p>
          </li>
        </ul>
      </section>
    </div>
    <div v-else class="summary-empty">
      尚未生成 AI 内容。开始分析后，内容概览、章节与关键内容会显示在这里。
    </div>
  </section>
</template>
