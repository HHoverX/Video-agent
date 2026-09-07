import { createSHA256 } from 'hash-wasm'

export const HASH_CHUNK_SIZE = 4 * 1024 * 1024

export async function hashBlobIncrementally(
  blob: Blob,
  chunkSize = HASH_CHUNK_SIZE,
  onProgress?: (processedBytes: number, totalBytes: number) => void,
): Promise<string> {
  if (chunkSize <= 0) throw new Error('hash chunk size must be positive')

  const hasher = await createSHA256()
  hasher.init()
  for (let offset = 0; offset < blob.size; offset += chunkSize) {
    const chunk = blob.slice(offset, Math.min(offset + chunkSize, blob.size))
    const buffer = await chunk.arrayBuffer()
    hasher.update(new Uint8Array(buffer))
    onProgress?.(Math.min(offset + chunk.size, blob.size), blob.size)
  }
  return hasher.digest('hex') as string
}
