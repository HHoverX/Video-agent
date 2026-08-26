import type { AnalysisTask } from '@/types/analysis'

export interface AnalysisRecoveryDecision {
  task: AnalysisTask | null
  resolved: boolean
  storageTaskId: number | null
  shouldConnect: boolean
}

export function resolveAnalysisRecovery(
  storedTaskId: number | null,
  backendTask: AnalysisTask | null | undefined,
): AnalysisRecoveryDecision {
  if (backendTask === undefined) {
    return {
      task: null,
      resolved: false,
      storageTaskId: storedTaskId,
      shouldConnect: false,
    }
  }
  if (backendTask === null) {
    return {
      task: null,
      resolved: true,
      storageTaskId: null,
      shouldConnect: false,
    }
  }

  const shouldConnect = ['PENDING', 'PROCESSING', 'RETRY_WAITING'].includes(backendTask.status)
  return {
    task: backendTask,
    resolved: true,
    storageTaskId: backendTask.status === 'SUCCESS' ? null : backendTask.taskId,
    shouldConnect,
  }
}
