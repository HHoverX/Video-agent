import { api } from './api'
import type { TranscriptSegment } from '@/types/transcript'

export async function getVideoTranscript(videoId: number): Promise<TranscriptSegment[]> {
  const { data } = await api.get<TranscriptSegment[]>(`/videos/${videoId}/transcript`)
  return data
}
