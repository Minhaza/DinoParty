import { apiRequest, ApiError } from './client'
import type { GameDetailDto, GameSummaryDto, SettingField } from './types'

export const gameQueryKeys = {
  all: ['games'] as const,
  detail: (key: string) => ['games', key] as const,
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function parseSummary(value: unknown): GameSummaryDto {
  if (
    !isObject(value) ||
    typeof value.key !== 'string' ||
    typeof value.name !== 'string' ||
    typeof value.minPlayers !== 'number' ||
    typeof value.maxPlayers !== 'number' ||
    typeof value.enabled !== 'boolean'
  ) {
    throw new ApiError(502, null)
  }
  return {
    key: value.key,
    name: value.name,
    tagline: typeof value.tagline === 'string' ? value.tagline : '',
    minPlayers: value.minPlayers,
    maxPlayers: value.maxPlayers,
    iconKey: typeof value.iconKey === 'string' ? value.iconKey : value.key,
    enabled: value.enabled,
  }
}

const SETTING_TYPES = new Set(['int', 'bool', 'enum', 'list'])

/** A field of a type this client does not know is left out, so a newer server cannot crash the form. */
function isKnownField(value: unknown): value is SettingField {
  return (
    isObject(value) &&
    typeof value.key === 'string' &&
    typeof value.label === 'string' &&
    typeof value.type === 'string' &&
    SETTING_TYPES.has(value.type)
  )
}

/** `GET /games`: every registered game in key order, disabled ones with `enabled:false`. */
export async function listGames(signal?: AbortSignal): Promise<GameSummaryDto[]> {
  const body = await apiRequest('/games', { signal })
  if (!Array.isArray(body)) throw new ApiError(502, null)
  return body.map(parseSummary)
}

/** `GET /games/{key}`: bounds, settings schema and defaults; `404 GAME_NOT_FOUND` for unknown keys. */
export async function getGame(key: string, signal?: AbortSignal): Promise<GameDetailDto> {
  const body = await apiRequest(`/games/${encodeURIComponent(key)}`, { signal })
  const summary = parseSummary(body)
  if (!isObject(body) || !Array.isArray(body.settingsSchema) || !isObject(body.defaultSettings)) {
    throw new ApiError(502, null)
  }
  return {
    ...summary,
    description: typeof body.description === 'string' ? body.description : '',
    howToPlay: Array.isArray(body.howToPlay)
      ? body.howToPlay.filter((s): s is string => typeof s === 'string')
      : [],
    settingsSchema: body.settingsSchema.filter(isKnownField),
    defaultSettings: body.defaultSettings as GameDetailDto['defaultSettings'],
  }
}
