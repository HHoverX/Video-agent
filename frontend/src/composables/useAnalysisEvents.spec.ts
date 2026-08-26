import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useAnalysisEvents } from './useAnalysisEvents'
import { useAuthStore } from '@/stores/auth'

function sseResponse(...frames: string[]) {
  const encoder = new TextEncoder()
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const frame of frames) controller.enqueue(encoder.encode(frame))
      controller.close()
    },
  })
  return new Response(stream, { status: 200 })
}

async function flush() {
  await Promise.resolve()
  await Promise.resolve()
}

describe('useAnalysisEvents', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    setActivePinia(createPinia())
    useAuthStore().token = 'test-token'
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    fetchMock.mockReset()
  })

  it('connects to the task event URL with Bearer authorization', async () => {
    fetchMock.mockResolvedValue(sseResponse())

    useAnalysisEvents({ onProgress: vi.fn(), onError: vi.fn() }).connect(42)
    await flush()

    expect(fetchMock).toHaveBeenCalledWith('/api/analysis/42/events', expect.objectContaining({
      method: 'GET',
      headers: {
        Accept: 'text/event-stream',
        Authorization: 'Bearer test-token',
      },
    }))
  })

  it('parses progress SSE frames and calls onProgress', async () => {
    const onProgress = vi.fn()
    fetchMock.mockResolvedValue(sseResponse(
      'event: progress\ndata: {"task_id":42,"status":"PROCESSING","progress":50}\n\n',
    ))

    useAnalysisEvents({ onProgress, onError: vi.fn() }).connect(42)
    await flush()

    expect(onProgress).toHaveBeenCalledWith({ task_id: 42, status: 'PROCESSING', progress: 50 })
  })

  it('calls onOpen after a successful response with a readable body', async () => {
    const onOpen = vi.fn()
    fetchMock.mockResolvedValue(sseResponse())

    useAnalysisEvents({ onProgress: vi.fn(), onError: vi.fn(), onOpen }).connect(42)
    await flush()

    expect(onOpen).toHaveBeenCalledTimes(1)
  })

  it('calls onError with the task ID when the request or stream fails', async () => {
    const requestError = vi.fn()
    fetchMock.mockRejectedValueOnce(new Error('network failure'))
    useAnalysisEvents({ onProgress: vi.fn(), onError: requestError }).connect(42)
    await flush()

    expect(requestError).toHaveBeenCalledWith(42)

    const streamError = vi.fn()
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.error(new Error('stream failure'))
      },
    })
    fetchMock.mockResolvedValueOnce(new Response(stream, { status: 200 }))
    useAnalysisEvents({ onProgress: vi.fn(), onError: streamError }).connect(43)
    await flush()

    expect(streamError).toHaveBeenCalledWith(43)
  })

  it('aborts the current request when closed', () => {
    let signal: AbortSignal | undefined
    fetchMock.mockImplementation((_url: string, options: RequestInit) => {
      signal = options.signal ?? undefined
      return new Promise<Response>(() => {})
    })
    const events = useAnalysisEvents({ onProgress: vi.fn(), onError: vi.fn() })

    events.connect(42)
    events.close()

    expect(signal?.aborted).toBe(true)
  })

  it('aborts the previous request when connecting again', () => {
    const signals: AbortSignal[] = []
    fetchMock.mockImplementation((_url: string, options: RequestInit) => {
      signals.push(options.signal as AbortSignal)
      return new Promise<Response>(() => {})
    })
    const events = useAnalysisEvents({ onProgress: vi.fn(), onError: vi.fn() })

    events.connect(42)
    events.connect(43)

    expect(signals[0].aborted).toBe(true)
    expect(signals[1].aborted).toBe(false)
  })

  it('does not report intentional AbortError failures', async () => {
    const onError = vi.fn()
    let rejectFetch!: (error: Error) => void
    fetchMock.mockImplementation(() => new Promise<Response>((_resolve, reject) => {
      rejectFetch = reject
    }))
    const events = useAnalysisEvents({ onProgress: vi.fn(), onError })

    events.connect(42)
    events.close()
    rejectFetch(new DOMException('The operation was aborted', 'AbortError'))
    await flush()

    expect(onError).not.toHaveBeenCalled()
  })
})
