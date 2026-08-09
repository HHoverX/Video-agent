import { analysisEventsUrl } from '@/services/analysis'
import type { AnalysisProgressEvent } from '@/types/analysis'

interface AnalysisEventHandlers {
  onProgress: (event: AnalysisProgressEvent) => void
  onError: (taskId: number) => void
  onOpen?: () => void
}

export function useAnalysisEvents(handlers: AnalysisEventHandlers) {
  let eventSource: EventSource | null = null

  function close() {
    eventSource?.close()
    eventSource = null
  }

  function connect(taskId: number) {
    close()
    const source = new EventSource(analysisEventsUrl(taskId))
    eventSource = source

    source.onopen = () => handlers.onOpen?.()
    source.addEventListener('progress', (rawEvent) => {
      try {
        handlers.onProgress(JSON.parse(rawEvent.data) as AnalysisProgressEvent)
      } catch {
        close()
        handlers.onError(taskId)
      }
    })
    source.onerror = () => {
      close()
      handlers.onError(taskId)
    }
  }

  return { connect, close }
}
