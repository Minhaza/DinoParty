import type { GameKey, SettingsMap } from '@/api/types'

export interface SettingChoice {
  value: number
  label: string
}

export interface SettingFieldConfig {
  key: string
  label: string
  choices: SettingChoice[]
  defaultValue: number
}

export interface GameConfig {
  key: GameKey
  name: string
  minPlayers: number
  maxPlayers: number
  defaultMaxPlayers: number
  fields: SettingFieldConfig[]
}

const seconds = (values: number[]): SettingChoice[] =>
  values.map((value) => ({ value, label: `${value} seconds` }))

/**
 * Host-selectable choices per game. Bounds mirror docs/api-contracts.md §2.2 / game-rules.md; the
 * server validates again and is the authority.
 */
export const GAME_CONFIGS: Record<GameKey, GameConfig> = {
  'draw-guess': {
    key: 'draw-guess',
    name: 'Draw & Guess',
    minPlayers: 2,
    maxPlayers: 10,
    defaultMaxPlayers: 8,
    fields: [
      {
        key: 'cycles',
        label: 'Turns per player',
        defaultValue: 2,
        choices: [1, 2, 3, 4, 5].map((value) => ({ value, label: String(value) })),
      },
      {
        key: 'drawSeconds',
        label: 'Drawing time',
        defaultValue: 80,
        choices: seconds([60, 80, 90, 120]),
      },
    ],
  },
  telephone: {
    key: 'telephone',
    name: 'Dino Telephone',
    minPlayers: 4,
    maxPlayers: 8,
    defaultMaxPlayers: 8,
    fields: [
      {
        key: 'promptSeconds',
        label: 'Prompt time',
        defaultValue: 60,
        choices: seconds([30, 60, 90]),
      },
      {
        key: 'drawSeconds',
        label: 'Drawing time',
        defaultValue: 90,
        choices: seconds([60, 90, 120]),
      },
      {
        key: 'describeSeconds',
        label: 'Describing time',
        defaultValue: 45,
        choices: seconds([30, 45, 60]),
      },
    ],
  },
}

export function isGameKey(value: string): value is GameKey {
  return value in GAME_CONFIGS
}

export function defaultSettings(game: GameKey): SettingsMap {
  return Object.fromEntries(GAME_CONFIGS[game].fields.map((f) => [f.key, f.defaultValue]))
}

export function maxPlayerChoices(game: GameKey): number[] {
  const { minPlayers, maxPlayers } = GAME_CONFIGS[game]
  return Array.from({ length: maxPlayers - minPlayers + 1 }, (_, i) => minPlayers + i)
}

/** Room codes: 6 characters from an alphabet without look-alikes (docs/api-contracts.md §1). */
export const ROOM_CODE_PATTERN = /^[ABCDEFGHJKMNPQRSTUVWXYZ2-9]{6}$/
