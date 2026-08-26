import { api } from './api'
import type { AnalysisTask, StartAnalysisResponse } from '@/types/analysis'

export async function startAnalysis(videoId: number): Promise<StartAnalysisResponse> {
  const { data } = await api.post<StartAnalysisResponse>(`/videos/${videoId}/analysis`)
  return data
}

export async function getAnalysisTask(taskId: number): Promise<AnalysisTask> {
  const { data } = await api.get<AnalysisTask>(`/analysis/${taskId}`)
  return data
}

export async function getCurrentAnalysisTask(videoId: number): Promise<AnalysisTask | null> {
  const response = await api.get<AnalysisTask>(`/videos/${videoId}/analysis`)
  return response.status === 204 ? null : response.data
}

export function analysisEventsUrl(taskId: number): string {
  const baseUrl = import.meta.env.VITE_API_BASE_URL ?? '/api'
  return `${baseUrl.replace(/\/$/, '')}/analysis/${encodeURIComponent(taskId)}/events`
}
