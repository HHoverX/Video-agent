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
