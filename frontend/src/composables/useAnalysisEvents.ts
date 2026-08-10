import { analysisEventsUrl } from '@/services/analysis'
import { useAuthStore } from '@/stores/auth'
import type { AnalysisProgressEvent } from '@/types/analysis'

interface AnalysisEventHandlers {
  onProgress: (event: AnalysisProgressEvent) => void
  onError: (taskId: number) => void
  onOpen?: () => void
}

export function useAnalysisEvents(handlers: AnalysisEventHandlers) {
  const auth = useAuthStore()
  let abortController: AbortController | null = null

  function close() {
    abortController?.abort()
    abortController = null
  }

  function dispatchFrame(frame: string): boolean {
    let eventName = 'message'
    const data: string[] = []
    for (const line of frame.split(/\r?\n/)) {
      if (line.startsWith('event:')) eventName = line.slice(6).trim()
      if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
    }
    if (eventName !== 'progress' || data.length === 0) return true

    const event = JSON.parse(data.join('\n')) as AnalysisProgressEvent
    handlers.onProgress(event)
    return event.status !== 'SUCCESS' && event.status !== 'FAILED'
  }

  async function consume(taskId: number, controller: AbortController) {
    const response = await fetch(analysisEventsUrl(taskId), {
      method: 'GET',
      headers: {
        Accept: 'text/event-stream',
        Authorization: `Bearer ${auth.token}`,
      },
      signal: controller.signal,
    })
    if (!response.ok || !response.body) {
      throw new Error(`SSE request failed with status ${response.status}`)
    }
    handlers.onOpen?.()

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (!controller.signal.aborted) {
      const { done, value } = await reader.read()
      buffer += decoder.decode(value, { stream: !done })
      let boundary = buffer.search(/\r?\n\r?\n/)
      while (boundary >= 0) {
        const frame = buffer.slice(0, boundary)
        const separator = buffer.slice(boundary).match(/^\r?\n\r?\n/)?.[0] ?? '\n\n'
        buffer = buffer.slice(boundary + separator.length)
        if (frame && !dispatchFrame(frame)) {
          close()
          return
        }
        boundary = buffer.search(/\r?\n\r?\n/)
      }
      if (done) return
    }
  }

  function connect(taskId: number) {
    close()
    const controller = new AbortController()
    abortController = controller
    void consume(taskId, controller).catch((error: unknown) => {
      if (controller.signal.aborted) return
      if (abortController === controller) {
        abortController = null
        handlers.onError(taskId)
      }
    })
  }

  return { connect, close }
}
