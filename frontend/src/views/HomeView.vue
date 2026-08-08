<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { getHealth, type HealthResponse } from '@/services/api'

const loading = ref(true)
const health = ref<HealthResponse | null>(null)
const errorMessage = ref('')

const isHealthy = computed(() => health.value?.status === 'UP')

async function refreshHealth() {
  loading.value = true
  errorMessage.value = ''

  try {
    health.value = await getHealth()
  } catch {
    health.value = null
    errorMessage.value = '后端暂未连接，请确认基础设施与 API 已启动。'
  } finally {
    loading.value = false
  }
}

onMounted(refreshHealth)
</script>

<template>
  <main class="shell">
    <nav class="nav">
      <a class="brand" href="/" aria-label="VideoAgent 首页">
        <span class="brand-mark">V</span>
        <span>VideoAgent</span>
      </a>
      <span class="milestone-tag">Milestone 1</span>
    </nav>

    <section class="hero">
      <div class="hero-copy">
        <p class="eyebrow">AI VIDEO WORKSPACE</p>
        <h1>让每一段视频<br /><span>变得可理解、可检索。</span></h1>
        <p class="intro">
          上传视频，异步完成音频提取、语音转录和结构化总结，
          并保留可跳转的时间戳。
        </p>
        <div class="coming-soon">
          <span class="pulse-dot"></span>
          项目骨架已就绪，上传能力将在下一阶段开放
        </div>
      </div>

      <aside class="status-card">
        <div class="status-card__header">
          <div>
            <p class="card-label">SYSTEM STATUS</p>
            <h2>服务连接</h2>
          </div>
          <el-button :loading="loading" circle aria-label="刷新健康状态" @click="refreshHealth">
            ↻
          </el-button>
        </div>

        <div v-if="loading" class="status-row muted">
          <span class="status-indicator waiting"></span>
          正在检查后端 API…
        </div>
        <div v-else-if="isHealthy" class="status-row success">
          <span class="status-indicator online"></span>
          <div>
            <strong>API 运行正常</strong>
            <small>{{ health?.application }}</small>
          </div>
        </div>
        <div v-else class="status-row danger">
          <span class="status-indicator offline"></span>
          <div>
            <strong>API 未连接</strong>
            <small>{{ errorMessage }}</small>
          </div>
        </div>

        <div class="pipeline">
          <div class="pipeline-step active"><span>01</span> 上传</div>
          <div class="pipeline-line"></div>
          <div class="pipeline-step"><span>02</span> 转录</div>
          <div class="pipeline-line"></div>
          <div class="pipeline-step"><span>03</span> 总结</div>
        </div>
      </aside>
    </section>

    <footer>
      <span>Spring Boot · Vue 3 · MySQL · Redis · RocketMQ · MinIO</span>
      <span>VideoAgent V1</span>
    </footer>
  </main>
</template>

