<script setup lang="ts">
import { computed, ref } from 'vue'

import { formatTimestamp } from '@/utils/formatTimestamp'
import type { AgenticQaResponse, RagIndexStatusResponse } from '@/services/rag'

const props = defineProps<{
  ragStatus: RagIndexStatusResponse | null
  loading: boolean
  building: boolean
  error: string
  result: AgenticQaResponse | null
}>()

const emit = defineEmits<{
  ask: [question: string]
  prepare: []
}>()

const question = ref('')
const canAsk = computed(
  () => props.ragStatus?.mode === 'DIRECT_CONTEXT' || props.ragStatus?.status === 'READY',
)

function ask() {
  const value = question.value.trim()
  if (value && !props.loading) emit('ask', value)
}
</script>

<template>
  <section class="qa-panel content-panel">
    <div class="qa-panel__heading">
      <div>
        <h2>视频问答</h2>
        <p>根据视频内容提问，回答会附上相关片段。</p>
      </div>
    </div>

    <div v-if="error" class="notice notice--error qa-notice">{{ error }}</div>

    <div v-if="!ragStatus" class="qa-body">
      <p class="qa-hint">正在加载视频问答…</p>
    </div>
    <div v-else-if="canAsk" class="qa-body">
      <div class="qa-input-row">
        <el-input
          v-model="question"
          placeholder="输入关于视频内容的问题…"
          :disabled="loading"
          @keyup.enter="ask"
        />
        <el-button type="primary" :loading="loading" @click="ask">提问</el-button>
      </div>
    </div>
    <div v-else-if="ragStatus.status === 'NOT_BUILT'" class="qa-body">
      <p class="qa-hint">该视频内容较长，需要先准备视频问答。</p>
      <el-button :loading="building" @click="emit('prepare')">准备视频问答</el-button>
    </div>
    <div v-else-if="ragStatus.status === 'BUILDING'" class="qa-body">
      <p class="qa-hint">正在准备视频问答…</p>
    </div>
    <div v-else-if="ragStatus.status === 'FAILED'" class="qa-body">
      <p class="qa-hint">问答准备失败：{{ ragStatus.lastErrorMessage || '未知错误' }}</p>
      <el-button :loading="building" @click="emit('prepare')">重新准备</el-button>
    </div>

    <div v-if="result" class="qa-result">
      <div class="qa-answer">{{ result.answer }}</div>
      <div v-if="result.citations.length" class="qa-citations">
        <p class="qa-citations-title">相关片段</p>
        <ul class="qa-citation-list">
          <li v-for="(citation, index) in result.citations" :key="index">
            <span v-if="citation.startMs !== null && citation.endMs !== null" class="qa-citation-time">
              [{{ formatTimestamp(citation.startMs) }} – {{ formatTimestamp(citation.endMs) }}]
            </span>
            <p>{{ citation.text }}</p>
          </li>
        </ul>
      </div>
    </div>
  </section>
</template>
