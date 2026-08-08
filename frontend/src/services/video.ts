import axios from 'axios'

import { api } from './api'
import type { ApiErrorResponse, Video, VideoUploadResponse } from '@/types/video'

export async function listVideos(): Promise<Video[]> {
  const { data } = await api.get<Video[]>('/videos')
  return data
}

export async function getVideo(videoId: number): Promise<Video> {
  const { data } = await api.get<Video>(`/videos/${videoId}`)
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

export function apiErrorMessage(error: unknown, fallback: string): string {
  if (axios.isAxiosError<ApiErrorResponse>(error) && error.response?.data?.message) {
    return error.response.data.message
  }
  return fallback
}
