import { describe, expect, it } from 'vitest'

import { resolveAnalysisRecovery } from './analysisRecovery'
import type { AnalysisStatus, AnalysisTask } from '@/types/analysis'

describe('resolveAnalysisRecovery', () => {
  it('recovers an active backend task without session storage', () => {
    expect(resolveAnalysisRecovery(null, task('PROCESSING', 101))).toEqual({
      task: task('PROCESSING', 101),
      resolved: true,
      storageTaskId: 101,
      shouldConnect: true,
    })
  })

  it('uses the backend task when session storage is stale', () => {
    expect(resolveAnalysisRecovery(99, task('PENDING', 101))).toMatchObject({
      task: task('PENDING', 101),
      resolved: true,
      storageTaskId: 101,
      shouldConnect: true,
    })
  })

  it('clears stale session storage when the backend has no current task', () => {
    expect(resolveAnalysisRecovery(99, null)).toEqual({
      task: null,
      resolved: true,
      storageTaskId: null,
      shouldConnect: false,
    })
  })

  it('keeps a failed task without reconnecting', () => {
    expect(resolveAnalysisRecovery(101, task('FAILED', 101))).toMatchObject({
      task: task('FAILED', 101),
      resolved: true,
      storageTaskId: 101,
      shouldConnect: false,
    })
  })

  it('clears completed task storage without reconnecting', () => {
    expect(resolveAnalysisRecovery(101, task('SUCCESS', 101))).toMatchObject({
      task: task('SUCCESS', 101),
      resolved: true,
      storageTaskId: null,
      shouldConnect: false,
    })
  })

  it.each<AnalysisStatus>(['PENDING', 'PROCESSING', 'RETRY_WAITING'])(
    'connects each active status: %s',
    (status) => {
      expect(resolveAnalysisRecovery(null, task(status, 101)).shouldConnect).toBe(true)
    },
  )

  it('does not interpret a backend request failure as no task', () => {
    expect(resolveAnalysisRecovery(101, undefined)).toEqual({
      task: null,
      resolved: false,
      storageTaskId: 101,
      shouldConnect: false,
    })
  })
})

function task(status: AnalysisStatus, taskId: number): AnalysisTask {
  return {
    taskId,
    videoId: 42,
    status,
    stage: status === 'SUCCESS' ? 'DONE' : status === 'FAILED' ? 'FAILED' : 'ANALYZING',
    progress: status === 'SUCCESS' ? 100 : 40,
    message: '状态更新',
    errorCode: null,
    errorMessage: null,
    createdAt: '2026-08-26T10:00:00Z',
    startedAt: '2026-08-26T10:01:00Z',
    finishedAt: null,
  }
}
