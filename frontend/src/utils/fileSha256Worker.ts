export interface FileHashTask {
  promise: Promise<string>
  cancel: () => void
}

interface HashWorkerResponse {
  type: 'progress' | 'complete' | 'error'
  sha256?: string
  message?: string
}

export function startFileHash(file: File): FileHashTask {
  const worker = new Worker(new URL('../workers/sha256.worker.ts', import.meta.url), { type: 'module' })
  let settled = false
  let rejectTask: (reason?: unknown) => void = () => undefined

  const promise = new Promise<string>((resolve, reject) => {
    rejectTask = reject
    worker.onmessage = (event: MessageEvent<HashWorkerResponse>) => {
      if (settled || event.data.type === 'progress') return
      settled = true
      worker.terminate()
      if (event.data.type === 'complete' && event.data.sha256) {
        resolve(event.data.sha256)
      } else {
        reject(new Error(event.data.message || '文件 SHA-256 计算失败'))
      }
    }
    worker.onerror = (event) => {
      if (settled) return
      settled = true
      worker.terminate()
      reject(new Error(event.message || '文件 SHA-256 Worker 运行失败'))
    }
    worker.postMessage({ file })
  })

  return {
    promise,
    cancel() {
      if (settled) return
      settled = true
      worker.terminate()
      rejectTask(new DOMException('文件 SHA-256 计算已取消', 'AbortError'))
    },
  }
}
