export type AnalysisStatus = 'PENDING' | 'PROCESSING' | 'RETRY_WAITING' | 'SUCCESS' | 'FAILED'

export type AnalysisStage =
  | 'QUEUED'
  | 'PREPARING'
  | 'EXTRACTING_AUDIO'
  | 'TRANSCRIBING'
  | 'SAVING_TRANSCRIPT'
  | 'SUMMARIZING'
  | 'ANALYZING'
  | 'PROCESSING'
  | 'SAVING'
  | 'DONE'
  | 'FAILED'

export interface StartAnalysisResponse {
  taskId: number
  videoId: number
  status: AnalysisStatus
}

export interface AnalysisTask {
  taskId: number
  videoId: number
  status: AnalysisStatus
  stage: AnalysisStage
  progress: number
  message: string
  errorCode: string | null
  errorMessage: string | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
}

export interface AnalysisProgressEvent {
  taskId: number
  videoId: number
  status: AnalysisStatus
  stage: AnalysisStage
  progress: number
  message: string
  errorCode: string | null
  errorMessage: string | null
}
