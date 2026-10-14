import { apiJson, apiRequest, ApiError } from './client'
import type {
  CreateRoomInput,
  ResultsDto,
  RoomDto,
  RoomPreviewDto,
  SnapshotDto,
  UpdateRoomSettingsInput,
} from './types'

export const roomQueryKey = (code: string) => ['room', code] as const

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

export function isRoomDto(value: RoomDto | RoomPreviewDto): value is RoomDto {
  return 'players' in value
}

function parseRoom(value: unknown): RoomDto {
  if (!isObject(value) || typeof value.code !== 'string' || !Array.isArray(value.players)) {
    throw new ApiError(502, null)
  }
  return value as unknown as RoomDto
}

export async function createRoom(input: CreateRoomInput): Promise<RoomDto> {
  return parseRoom(await apiJson('/rooms', 'POST', input))
}

/** Members get full details, anyone else a preview (docs/api-contracts.md §2). */
export async function getRoom(
  code: string,
  signal?: AbortSignal,
): Promise<RoomDto | RoomPreviewDto> {
  const body = await apiRequest(`/rooms/${encodeURIComponent(code)}`, { signal })
  if (isObject(body) && Array.isArray(body.players)) return parseRoom(body)
  if (isObject(body) && typeof body.code === 'string' && typeof body.playerCount === 'number') {
    return body as unknown as RoomPreviewDto
  }
  throw new ApiError(502, null)
}

export async function joinRoom(code: string): Promise<RoomDto> {
  return parseRoom(await apiJson(`/rooms/${encodeURIComponent(code)}/join`, 'POST', {}))
}

export async function leaveRoom(code: string): Promise<void> {
  await apiRequest(`/rooms/${encodeURIComponent(code)}/leave`, { method: 'POST' })
}

export async function getRoomSnapshot(code: string, signal?: AbortSignal): Promise<SnapshotDto> {
  const body = await apiRequest(`/rooms/${encodeURIComponent(code)}/snapshot`, { signal })
  if (
    !isObject(body) ||
    !isObject(body.room) ||
    typeof body.serverNow !== 'string' ||
    !isObject(body.seq)
  ) {
    throw new ApiError(502, null)
  }
  return body as unknown as SnapshotDto
}

export async function setRoomReady(
  code: string,
  ready: boolean,
  signal?: AbortSignal,
): Promise<RoomDto> {
  return parseRoom(
    await apiRequest(`/rooms/${encodeURIComponent(code)}/ready`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ready }),
      signal,
    }),
  )
}

export async function startRoom(code: string, signal?: AbortSignal): Promise<RoomDto> {
  return parseRoom(
    await apiRequest(`/rooms/${encodeURIComponent(code)}/start`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({}),
      signal,
    }),
  )
}

export async function kickPlayer(code: string, guestId: string): Promise<void> {
  await apiJson(`/rooms/${encodeURIComponent(code)}/kick`, 'POST', { guestId })
}

export async function closeRoom(code: string): Promise<void> {
  await apiRequest(`/rooms/${encodeURIComponent(code)}/close`, { method: 'POST' })
}

export async function getSessionResults(
  sessionId: string,
  signal?: AbortSignal,
): Promise<ResultsDto> {
  const body = await apiRequest(`/sessions/${encodeURIComponent(sessionId)}/results`, { signal })
  if (!isObject(body) || typeof body.sessionId !== 'string') {
    throw new ApiError(502, null)
  }
  return body as unknown as ResultsDto
}

export async function updateRoomSettings(
  code: string,
  input: UpdateRoomSettingsInput,
): Promise<RoomDto> {
  return parseRoom(await apiJson(`/rooms/${encodeURIComponent(code)}/settings`, 'PATCH', input))
}
