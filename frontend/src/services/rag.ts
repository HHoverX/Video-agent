import { api } from './api'

export type QaContextMode = 'DIRECT_CONTEXT' | 'RAG'
export type RagIndexStatus = 'NOT_REQUIRED' | 'NOT_BUILT' | 'BUILDING' | 'READY' | 'FAILED'

export interface RagIndexStatusResponse {
  mode: QaContextMode
  status: RagIndexStatus
  chunkCount: number | null
  embeddingModel: string | null
  transcriptChars: number | null
  lastErrorCode: string | null
  lastErrorMessage: string | null
}

export interface QaCitation {
  startMs: number
  endMs: number
  text: string
}

export interface QaResponse {
  mode: QaContextMode
  answer: string
  citations: QaCitation[]
}

export async function getRagStatus(videoId: number): Promise<RagIndexStatusResponse> {
  const { data } = await api.get<RagIndexStatusResponse>(`/videos/${videoId}/rag/status`)
  return data
}

export async function buildRagIndex(videoId: number): Promise<RagIndexStatusResponse> {
  const { data } = await api.post<RagIndexStatusResponse>(`/videos/${videoId}/rag/index`)
  return data
}

export async function askVideoQa(videoId: number, question: string): Promise<QaResponse> {
  const { data } = await api.post<QaResponse>(`/videos/${videoId}/qa`, { question })
  return data
}
