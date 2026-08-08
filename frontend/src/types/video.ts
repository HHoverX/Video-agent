export interface Video {
  id: number
  title: string
  originalFilename: string
  fileSize: number
  durationSeconds: number | null
  mimeType: string
  status: 'UPLOADED'
  createdAt: string
  updatedAt: string
}

export interface VideoUploadResponse {
  videoId: number
}

export interface ApiErrorResponse {
  timestamp: string
  status: number
  code: string
  message: string
  path: string
}
