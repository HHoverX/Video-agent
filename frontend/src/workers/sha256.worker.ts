/// <reference lib="webworker" />

import { hashBlobIncrementally } from './fileSha256'

interface HashRequest {
  file: File
}

interface HashResponse {
  type: 'progress' | 'complete' | 'error'
  processedBytes?: number
  totalBytes?: number
  sha256?: string
  message?: string
}

self.onmessage = async (event: MessageEvent<HashRequest>) => {
  try {
    const sha256 = await hashBlobIncrementally(event.data.file, undefined, (processedBytes, totalBytes) => {
      self.postMessage({ type: 'progress', processedBytes, totalBytes } satisfies HashResponse)
    })
    self.postMessage({ type: 'complete', sha256 } satisfies HashResponse)
  } catch (error) {
    self.postMessage({
      type: 'error',
      message: error instanceof Error ? error.message : '文件 SHA-256 计算失败',
    } satisfies HashResponse)
  }
}
