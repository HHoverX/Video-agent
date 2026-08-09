export interface VideoSummary {
  taskId: number
  overview: string
  createdAt: string
  updatedAt: string
}

export interface VideoChapter {
  chapterIndex: number
  title: string
  summary: string
  startMs: number
  endMs: number
}

export interface VideoKeyPoint {
  pointIndex: number
  content: string
  startMs: number
  endMs: number
}
