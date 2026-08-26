export interface UploadPart {
  partNumber: number
  size: number
  etag: string
  sha256?: string | null
}

export interface UploadSession {
  uploadId: string
  fileName: string
  title: string
  fileSize: number
  contentType: string
  chunkSize: number
  totalParts: number
  status: 'CREATED' | 'UPLOADING' | 'COMPLETING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'EXPIRED'
  expiresAt: string
  uploadedBytes: number
  completedParts: UploadPart[]
  maxConcurrency: number
  videoId?: number | null
  analysisTaskId?: number | null
  lastError?: string | null
}

export interface UploadPartUrl {
  partNumber: number
  expectedSize: number
  alreadyCompleted: boolean
  uploadUrl?: string | null
  expiresAt?: string | null
}

export interface CompleteUploadResult {
  uploadId: string
  videoId: number
  status: string
}
