import type { AxiosResponse } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { api } from './api'
import { getVideoPlaybackUrl } from './video'
import type { VideoPlaybackUrlResponse } from '@/types/video'

vi.mock('./api', () => ({
  api: {
    get: vi.fn(),
  },
}))

describe('video service', () => {
  const getMock = vi.mocked(api.get)

  beforeEach(() => {
    getMock.mockReset()
  })

  it('requests and returns the playback URL contract', async () => {
    const response: VideoPlaybackUrlResponse = {
      url: 'https://media.example.com/signed-get',
      expiresAt: '2026-08-26T11:00:00Z',
    }
    getMock.mockResolvedValue({ data: response } as AxiosResponse<VideoPlaybackUrlResponse>)

    await expect(getVideoPlaybackUrl(42)).resolves.toEqual(response)
    expect(getMock).toHaveBeenCalledWith('/videos/42/playback-url')
  })
})
