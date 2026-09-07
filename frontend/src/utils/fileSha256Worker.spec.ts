import { afterEach, describe, expect, it, vi } from 'vitest'

import { startFileHash } from './fileSha256Worker'

class FakeWorker {
  static instances: FakeWorker[] = []

  onmessage: ((event: MessageEvent) => void) | null = null
  onerror: ((event: ErrorEvent) => void) | null = null
  terminated = false

  constructor() {
    FakeWorker.instances.push(this)
  }

  postMessage() {}

  terminate() {
    this.terminated = true
  }
}

describe('startFileHash', () => {
  afterEach(() => {
    FakeWorker.instances = []
    vi.unstubAllGlobals()
  })

  it('reports worker errors and terminates the worker', async () => {
    vi.stubGlobal('Worker', FakeWorker)
    const task = startFileHash({} as File)
    const worker = FakeWorker.instances[0]

    worker.onerror?.({ message: 'worker failed' } as ErrorEvent)

    await expect(task.promise).rejects.toThrow('worker failed')
    expect(worker.terminated).toBe(true)
  })

  it('cancels an old task so its late result cannot replace a newer file hash', async () => {
    vi.stubGlobal('Worker', FakeWorker)
    const first = startFileHash({ name: 'first.mp4' } as File)
    const firstWorker = FakeWorker.instances[0]
    first.cancel()

    const second = startFileHash({ name: 'second.mp4' } as File)
    const secondWorker = FakeWorker.instances[1]
    firstWorker.onmessage?.({ data: { type: 'complete', sha256: 'old' } } as MessageEvent)
    secondWorker.onmessage?.({ data: { type: 'complete', sha256: 'new' } } as MessageEvent)

    await expect(first.promise).rejects.toMatchObject({ name: 'AbortError' })
    await expect(second.promise).resolves.toBe('new')
    expect(firstWorker.terminated).toBe(true)
  })
})
