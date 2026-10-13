import { apiJson, apiRequest, ApiError } from './client'
import type { CreateGuestResponse, GuestDto, GuestProfileInput } from './types'

export const guestQueryKey = ['guest', 'me'] as const

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function parseGuest(value: unknown): GuestDto {
  if (
    !isObject(value) ||
    typeof value.id !== 'string' ||
    typeof value.nickname !== 'string' ||
    typeof value.avatarKey !== 'string'
  ) {
    throw new ApiError(502, null)
  }
  return value as unknown as GuestDto
}

export async function createGuest(input: GuestProfileInput): Promise<CreateGuestResponse> {
  const body = await apiJson('/guests', 'POST', input)
  if (!isObject(body) || typeof body.token !== 'string') throw new ApiError(502, null)
  return { guest: parseGuest(body.guest), token: body.token, expiresAt: String(body.expiresAt) }
}

export async function getMe(signal?: AbortSignal): Promise<GuestDto> {
  return parseGuest(await apiRequest('/guests/me', { signal }))
}

export async function updateMe(input: Partial<GuestProfileInput>): Promise<GuestDto> {
  return parseGuest(await apiJson('/guests/me', 'PATCH', input))
}
