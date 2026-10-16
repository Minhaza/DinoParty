import type {
  AlbumDto,
  AlbumEntryDto,
  AlbumEntryView,
  AlbumsResponse,
} from '@/features/telephone/types'

import { apiRequest, ApiError } from './client'

// Dino Telephone albums after the game (api-contracts §2): read from the database, only for
// current members of the session's room, never while the session runs (`RESULTS_NOT_READY`).

/** Every key carries the session id, so albums of one game never appear in another. */
export const telephoneQueryKeys = {
  albums: (sessionId: string) => ['telephone', 'albums', sessionId] as const,
  album: (sessionId: string, chainIndex: number) =>
    ['telephone', 'album', sessionId, chainIndex] as const,
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

export async function getAlbums(sessionId: string, signal?: AbortSignal): Promise<AlbumsResponse> {
  const body = await apiRequest(`/sessions/${encodeURIComponent(sessionId)}/albums`, { signal })
  if (!isObject(body) || !Array.isArray(body.albums)) throw new ApiError(502, null)
  return body as unknown as AlbumsResponse
}

export async function getAlbum(
  sessionId: string,
  chainIndex: number,
  signal?: AbortSignal,
): Promise<AlbumDto> {
  const body = await apiRequest(`/sessions/${encodeURIComponent(sessionId)}/albums/${chainIndex}`, {
    signal,
  })
  if (!isObject(body) || !Array.isArray(body.entries)) throw new ApiError(502, null)
  return body as unknown as AlbumDto
}

const MAX_NOT_READY_RETRIES = 5

/**
 * Right after `GAME_FINISHED` the entries may still be on their way to the database; the server
 * says so with `RESULTS_NOT_READY`. Anything else (not a member, no such album) is final.
 */
export function shouldRetryAlbums(failureCount: number, error: unknown): boolean {
  return (
    error instanceof ApiError &&
    error.code === 'RESULTS_NOT_READY' &&
    failureCount < MAX_NOT_READY_RETRIES
  )
}

export const ALBUM_RETRY_DELAY_MS = 2000

/** The shared album view model of a stored entry (strokes are always present over REST). */
export function albumEntryView(entry: AlbumEntryDto): AlbumEntryView {
  return {
    step: entry.step,
    kind: entry.kind,
    authorGuestId: entry.authorGuestId,
    authorNickname: entry.authorNickname,
    text: entry.kind === 'TEXT' ? entry.text : null,
    strokes: entry.kind === 'DRAWING' ? entry.strokes : null,
    omitted: false,
    fill: entry.fill,
    timedOut: entry.timedOut,
    placeholder: entry.placeholder,
  }
}
