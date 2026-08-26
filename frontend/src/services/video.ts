import axios from 'axios'

import { api } from './api'
import type {
  ApiErrorResponse,
  Video,
  VideoPage,
  VideoPlaybackUrlResponse,
  VideoUploadResponse,
} from '@/types/video'

export async function listVideos(page = 1, size = 10, keyword = ''): Promise<VideoPage> {
  const { data } = await api.get<VideoPage>('/videos', {
    params: { page, size, keyword: keyword.trim() || undefined },
  })
  return data
}

export async function getVideo(videoId: number): Promise<Video> {
  const { data } = await api.get<Video>(`/videos/${videoId}`)
  return data
}

export async function getVideoPlaybackUrl(videoId: number): Promise<VideoPlaybackUrlResponse> {
  const { data } = await api.get<VideoPlaybackUrlResponse>(`/videos/${videoId}/playback-url`)
  return data
}

export async function uploadVideo(
  file: File,
  title: string,
  onProgress: (percentage: number) => void,
): Promise<VideoUploadResponse> {
  const formData = new FormData()
  formData.append('file', file)
  if (title.trim()) {
    formData.append('title', title.trim())
  }

  const { data } = await api.post<VideoUploadResponse>('/videos', formData, {
    timeout: 120_000,
    onUploadProgress(event) {
      if (event.total) {
        onProgress(Math.round((event.loaded * 100) / event.total))
      }
    },
  })
  return data
}

export async function updateVideoTitle(videoId: number, title: string): Promise<Video> {
  const { data } = await api.patch<Video>(`/videos/${videoId}`, { title: title.trim() })
  return data
}

export async function deleteVideo(videoId: number): Promise<void> {
  await api.delete(`/videos/${videoId}`)
}

export function apiErrorMessage(error: unknown, fallback: string): string {
  if (axios.isAxiosError<ApiErrorResponse>(error) && error.response?.data?.message) {
    return error.response.data.message
  }
  return fallback
}
