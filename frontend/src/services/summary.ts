import { api } from './api'
import type { VideoChapter, VideoKeyPoint, VideoSummary } from '@/types/summary'

export async function getVideoSummary(videoId: number): Promise<VideoSummary | null> {
  const response = await api.get<VideoSummary>(`/videos/${videoId}/summary`)
  return response.status === 204 ? null : response.data
}

export async function getVideoChapters(videoId: number): Promise<VideoChapter[]> {
  const { data } = await api.get<VideoChapter[]>(`/videos/${videoId}/chapters`)
  return data
}

export async function getVideoKeyPoints(videoId: number): Promise<VideoKeyPoint[]> {
  const { data } = await api.get<VideoKeyPoint[]>(`/videos/${videoId}/key-points`)
  return data
}
