import { afterEach, describe, expect, it, vi } from 'vitest'

import { albumDto, albumsResponse, REVEAL_SESSION } from '@/features/telephone/fixtures'
import { mockApi, problem } from '@/test/mockApi'

import { ApiError } from './client'
import {
  albumEntryView,
  getAlbum,
  getAlbums,
  shouldRetryAlbums,
  telephoneQueryKeys,
} from './telephone'

afterEach(() => vi.unstubAllGlobals())

describe('telephone albums API', () => {
  it('lists the albums of a session', async () => {
    const calls = mockApi({
      [`GET /sessions/${REVEAL_SESSION}/albums`]: { status: 200, body: albumsResponse },
    })
    await expect(getAlbums(REVEAL_SESSION)).resolves.toEqual(albumsResponse)
    expect(calls).toHaveLength(1)
  })

  it('reads one album', async () => {
    mockApi({ [`GET /sessions/${REVEAL_SESSION}/albums/2`]: { status: 200, body: albumDto(2) } })
    const album = await getAlbum(REVEAL_SESSION, 2)
    expect(album.entries.map((e) => e.step)).toEqual([0, 1, 2, 3])
  })

  it('rejects a malformed body instead of rendering it', async () => {
    mockApi({ [`GET /sessions/${REVEAL_SESSION}/albums/0`]: { status: 200, body: { nope: 1 } } })
    await expect(getAlbum(REVEAL_SESSION, 0)).rejects.toBeInstanceOf(ApiError)
  })

  it('keys every query by session, so another game never shows these albums', () => {
    expect(telephoneQueryKeys.albums('s-1')).not.toEqual(telephoneQueryKeys.albums('s-2'))
    expect(telephoneQueryKeys.album('s-1', 0)).not.toEqual(telephoneQueryKeys.album('s-2', 0))
  })

  it('retries only while the albums are not ready, a few times', async () => {
    mockApi({
      [`GET /sessions/${REVEAL_SESSION}/albums`]: problem(409, 'RESULTS_NOT_READY'),
    })
    const notReady = await getAlbums(REVEAL_SESSION).catch((e: unknown) => e)
    expect(shouldRetryAlbums(0, notReady)).toBe(true)
    expect(shouldRetryAlbums(5, notReady)).toBe(false)
    expect(shouldRetryAlbums(0, new ApiError(403, null))).toBe(false)
  })

  it('maps an album entry onto the shared album view', () => {
    const [prompt, drawing] = albumDto(0).entries
    expect(albumEntryView(prompt)).toMatchObject({ step: 0, kind: 'TEXT', text: 'Prompt 1' })
    expect(albumEntryView(drawing)).toMatchObject({ kind: 'DRAWING', omitted: false, text: null })
  })
})
