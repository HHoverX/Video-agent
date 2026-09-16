import { api } from './api'

export type RagIndexStatus = 'NOT_BUILT' | 'BUILDING' | 'READY' | 'FAILED'

export interface RagIndexStatusResponse {
  status: RagIndexStatus
  chunkCount: number | null
  embeddingModel: string | null
  lastErrorCode: string | null
  lastErrorMessage: string | null
}

export interface QaCitation {
  startMs: number
  endMs: number
  text: string
}

export interface AgenticCitation {
  sourceType: string
  startMs: number | null
  endMs: number | null
  text: string
}

export interface QaResponse {
  answer: string
  citations: QaCitation[]
}

export interface AgenticQaResponse {
  answer: string
  strategy: string
  toolsUsed: string[]
  citations: AgenticCitation[]
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

export async function askAgenticQa(videoId: number, question: string): Promise<AgenticQaResponse> {
  const { data } = await api.post<AgenticQaResponse>(`/videos/${videoId}/qa/agentic`, { question })
  return data
}
