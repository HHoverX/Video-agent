export interface UploadPart {
  partNumber: number
  size: number
  etag?: string | null
  sha256?: string | null
}

export interface UploadSession {
  uploadId: string
  deduplicated?: false
  fileName: string
  title: string
  fileSize: number
  contentType: string
  chunkSize: number
  totalParts: number
  partNumberBase: 0 | 1
  status: 'CREATED' | 'UPLOADING' | 'COMPLETING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'EXPIRED'
  expiresAt: string
  uploadedBytes: number
  completedParts: UploadPart[]
  maxConcurrency: number
  videoId?: number | null
  analysisTaskId?: number | null
  lastError?: string | null
}

export interface DeduplicatedUploadResult {
  uploadId: null
  deduplicated: true
  videoId: number
  status: 'COMPLETED'
}

export type CreateUploadSessionResult = UploadSession | DeduplicatedUploadResult

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
  reusedExistingVideo: boolean
}
