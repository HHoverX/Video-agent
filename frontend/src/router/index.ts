import { createRouter, createWebHistory } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      redirect: '/videos',
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { publicOnly: true },
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('@/views/RegisterView.vue'),
      meta: { publicOnly: true },
    },
    {
      path: '/videos',
      name: 'videos',
      component: () => import('@/views/VideoListView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/upload',
      name: 'video-upload',
      component: () => import('@/views/UploadView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/videos/:id',
      name: 'video-detail',
      component: () => import('@/views/VideoDetailView.vue'),
      meta: { requiresAuth: true },
    },
  ],
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.requiresAuth && !auth.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.meta.publicOnly && auth.isAuthenticated) {
    return { name: 'videos' }
  }
  return true
})

export default router
