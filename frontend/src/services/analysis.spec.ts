import type { AxiosResponse } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { api } from './api'
import { getCurrentAnalysisTask } from './analysis'
import type { AnalysisTask } from '@/types/analysis'

vi.mock('./api', () => ({
  api: {
    get: vi.fn(),
  },
}))

describe('analysis service', () => {
  const getMock = vi.mocked(api.get)

  beforeEach(() => {
    getMock.mockReset()
  })

  it('requests and returns the current analysis task', async () => {
    const task = analysisTask()
    getMock.mockResolvedValue({ status: 200, data: task } as AxiosResponse<AnalysisTask>)

    await expect(getCurrentAnalysisTask(42)).resolves.toEqual(task)
    expect(getMock).toHaveBeenCalledWith('/videos/42/analysis')
  })

  it('returns null only for a no-content response', async () => {
    getMock.mockResolvedValue({ status: 204, data: '' } as unknown as AxiosResponse<AnalysisTask>)

    await expect(getCurrentAnalysisTask(42)).resolves.toBeNull()
  })

  it('propagates a request failure', async () => {
    const failure = new Error('request failed')
    getMock.mockRejectedValue(failure)

    await expect(getCurrentAnalysisTask(42)).rejects.toBe(failure)
  })
})

function analysisTask(): AnalysisTask {
  return {
    taskId: 101,
    videoId: 42,
    status: 'PROCESSING',
    stage: 'ANALYZING',
    progress: 40,
    message: '正在分析',
    errorCode: null,
    errorMessage: null,
    createdAt: '2026-08-26T10:00:00Z',
    startedAt: '2026-08-26T10:01:00Z',
    finishedAt: null,
  }
}
