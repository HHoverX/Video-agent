<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <RouterLink class="brand" to="/" aria-label="VideoAgent 视频列表">
        <span class="brand-mark">V</span>
        <span>VideoAgent</span>
      </RouterLink>

      <nav class="app-nav" aria-label="主导航">
        <RouterLink to="/">视频库</RouterLink>
        <RouterLink class="nav-upload" to="/upload">上传视频</RouterLink>
      </nav>
    </header>

    <main class="app-main">
      <RouterView />
    </main>

    <footer class="app-footer">
      <span>VideoAgent · Milestone 4</span>
      <span>FFmpeg · Mock ASR · Timestamped Transcript</span>
    </footer>
  </div>
</template>
