import { apiJson, apiRequest, ApiError } from './client'
import type { CreateRoomInput, RoomDto, RoomPreviewDto, UpdateRoomSettingsInput } from './types'

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

export async function updateRoomSettings(
  code: string,
  input: UpdateRoomSettingsInput,
): Promise<RoomDto> {
  return parseRoom(await apiJson(`/rooms/${encodeURIComponent(code)}/settings`, 'PATCH', input))
}
