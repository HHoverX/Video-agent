import axios from 'axios'

import { api } from './api'
import type { CompleteUploadResult, UploadPart, UploadPartUrl, UploadSession } from '@/types/upload'

export async function createUploadSession(input: {
  fileName: string
  title: string
  fileSize: number
  contentType: string
  chunkSize?: number
}): Promise<UploadSession> {
  const { data } = await api.post<UploadSession>('/uploads', input)
  return data
}

export async function getUploadSession(uploadId: string): Promise<UploadSession> {
  const { data } = await api.get<UploadSession>(`/uploads/${uploadId}`)
  return data
}

export async function createPartUploadUrl(uploadId: string, partNumber: number): Promise<UploadPartUrl> {
  const { data } = await api.post<UploadPartUrl>(`/uploads/${uploadId}/parts/${partNumber}/url`)
  return data
}

export async function uploadPartDirect(
  url: string,
  blob: Blob,
  signal: AbortSignal,
  onProgress: (loaded: number) => void,
): Promise<void> {
  await axios.put(url, blob, {
    signal,
    timeout: 15 * 60_000,
    headers: { 'Content-Type': 'application/octet-stream' },
    onUploadProgress(event) {
      onProgress(event.loaded)
    },
  })
}

export async function confirmUploadPart(uploadId: string, partNumber: number): Promise<UploadPart> {
  const { data } = await api.post<UploadPart>(`/uploads/${uploadId}/parts/${partNumber}/complete`, {})
  return data
}

export async function completeUpload(uploadId: string): Promise<CompleteUploadResult> {
  const { data } = await api.post<CompleteUploadResult>(`/uploads/${uploadId}/complete`, undefined, {
    timeout: 5 * 60_000,
  })
  return data
}

export async function cancelUpload(uploadId: string): Promise<void> {
  await api.delete(`/uploads/${uploadId}`)
}
