import axios from 'axios'

export interface HealthResponse {
  status: 'UP' | 'DOWN'
  application: string
  timestamp: string
}

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api',
  timeout: 5_000,
})

export async function getHealth(): Promise<HealthResponse> {
  const { data } = await api.get<HealthResponse>('/health')
  return data
}

