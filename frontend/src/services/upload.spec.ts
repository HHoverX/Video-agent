import type { AxiosResponse } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { api } from './api'
import { completeUpload, createUploadSession } from './upload'
import type { CompleteUploadResult, CreateUploadSessionResult } from '@/types/upload'

vi.mock('./api', () => ({
  api: {
    post: vi.fn(),
  },
}))

describe('upload service', () => {
  const postMock = vi.mocked(api.post)

  beforeEach(() => {
    postMock.mockReset()
  })

  it('submits the client SHA-256 when creating an upload session', async () => {
    const input = {
      fileName: 'lesson.mp4',
      title: 'lesson',
      fileSize: 42,
      contentType: 'video/mp4',
      sha256: 'a'.repeat(64),
    }
    const result = { deduplicated: true, uploadId: null, videoId: 7, status: 'COMPLETED' } as const
    postMock.mockResolvedValue({ data: result } as AxiosResponse<CreateUploadSessionResult>)

    await expect(createUploadSession(input)).resolves.toEqual(result)
    expect(postMock).toHaveBeenCalledWith('/uploads', input)
  })

  it('submits the client SHA-256 when completing an upload', async () => {
    const result: CompleteUploadResult = {
      uploadId: 'u1',
      videoId: 7,
      status: 'COMPLETED',
      reusedExistingVideo: false,
    }
    postMock.mockResolvedValue({ data: result } as AxiosResponse<CompleteUploadResult>)

    await expect(completeUpload('u1', 'a'.repeat(64))).resolves.toEqual(result)
    expect(postMock).toHaveBeenCalledWith(
      '/uploads/u1/complete',
      { sha256: 'a'.repeat(64) },
      { timeout: 5 * 60_000 },
    )
  })
})
