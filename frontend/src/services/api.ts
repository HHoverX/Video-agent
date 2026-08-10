import axios from 'axios'
import type { Pinia } from 'pinia'
import type { Router } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

export interface HealthResponse {
  status: 'UP' | 'DOWN'
  application: string
  timestamp: string
}

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api',
  timeout: 15_000,
})

export function installApiInterceptors(pinia: Pinia, router: Router) {
  api.interceptors.request.use((config) => {
    const auth = useAuthStore(pinia)
    if (auth.token) {
      config.headers.set('Authorization', `Bearer ${auth.token}`)
    }
    return config
  })

  api.interceptors.response.use(
    (response) => response,
    (error: unknown) => {
      if (axios.isAxiosError(error) && error.response?.status === 401) {
        const auth = useAuthStore(pinia)
        auth.clearSession()
        const routeName = router.currentRoute.value.name
        if (routeName !== 'login' && routeName !== 'register') {
          void router.replace({
            name: 'login',
            query: { redirect: router.currentRoute.value.fullPath },
          })
        }
      }
      return Promise.reject(error)
    },
  )
}

export async function getHealth(): Promise<HealthResponse> {
  const { data } = await api.get<HealthResponse>('/health')
  return data
}
