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

export interface AgenticCitation {
  sourceType: string
  startMs: number | null
  endMs: number | null
  text: string
}

export interface QaResponse {
  mode: QaContextMode
  answer: string
  citations: QaCitation[]
}

export interface AgenticQaResponse {
  answer: string
  strategy: string
  contextMode: string | null
  toolsUsed: string[]
  citations: AgenticCitation[]
}

export function agenticStrategyLabel(strategy: string): string {
  switch (strategy) {
    case 'SUMMARY': return '摘要'
    case 'TIME_LOOKUP': return '时间定位'
    case 'SEMANTIC_SEARCH': return '语义检索'
    case 'MULTI_SEARCH': return '多路检索'
    case 'BASIC_FALLBACK': return '基础问答回退'
    default: return strategy
  }
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
