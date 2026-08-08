import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'videos',
      component: () => import('@/views/VideoListView.vue'),
    },
    {
      path: '/upload',
      name: 'video-upload',
      component: () => import('@/views/UploadView.vue'),
    },
    {
      path: '/videos/:id',
      name: 'video-detail',
      component: () => import('@/views/VideoDetailView.vue'),
    },
  ],
})

export default router
