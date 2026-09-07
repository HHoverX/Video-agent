import { createHash } from 'node:crypto'

import { describe, expect, it, vi } from 'vitest'

import { hashBlobIncrementally } from './fileSha256'

describe('hashBlobIncrementally', () => {
  it('matches a known SHA-256 value across multiple chunks', async () => {
    const blob = new Blob(['abc'])

    await expect(hashBlobIncrementally(blob, 1)).resolves.toBe(
      'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
    )
  })

  it('hashes an empty blob', async () => {
    await expect(hashBlobIncrementally(new Blob([]), 4)).resolves.toBe(
      'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    )
  })

  it('reads only sliced chunks and never calls arrayBuffer on the whole blob', async () => {
    const bytes = new Uint8Array([1, 2, 3, 4, 5, 6, 7])
    const blob = new Blob([bytes])
    const wholeFileRead = vi.spyOn(blob, 'arrayBuffer')
    const slice = vi.spyOn(blob, 'slice')

    const digest = await hashBlobIncrementally(blob, 3)

    expect(digest).toBe(createHash('sha256').update(bytes).digest('hex'))
    expect(slice).toHaveBeenCalledTimes(3)
    expect(wholeFileRead).not.toHaveBeenCalled()
  })
})
