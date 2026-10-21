import type { GameSummaryDto, IntSettingField, SettingField, SettingsValue } from '@/api/types'

/**
 * Presentation helpers for a game's server-provided `settingsSchema` (api-contracts §2.6). The
 * schema decides keys, labels, bounds and defaults; these only turn it into choices and labels.
 */

/** Ranges up to this size list every value; larger ones are stepped to keep the select short. */
const LIST_ALL_UP_TO = 10
const NICE_STEPS = [5, 10, 15, 30, 60]
const TARGET_OPTIONS = 10

/** Values offered for an int field: in-range values (stepped when large), auto value first. */
export function intChoices(field: IntSettingField, current: number): number[] {
  const { min, max } = field
  const values = new Set<number>()
  if (max - min <= LIST_ALL_UP_TO) {
    for (let v = min; v <= max; v++) values.add(v)
  } else {
    const step =
      NICE_STEPS.find((s) => (max - min) / s <= TARGET_OPTIONS) ?? NICE_STEPS[NICE_STEPS.length - 1]
    values.add(min)
    for (let v = Math.ceil(min / step) * step; v < max; v += step) values.add(v)
    values.add(max)
    for (const v of [field.default, current]) {
      if (Number.isInteger(v) && v >= min && v <= max) values.add(v)
    }
  }
  const sorted = [...values].sort((a, b) => a - b)
  return field.autoValue === undefined
    ? sorted
    : [field.autoValue, ...sorted.filter((v) => v !== field.autoValue)]
}

const humanize = (value: string) => {
  const words = value.replace(/[-_]+/g, ' ').trim()
  return words.charAt(0).toUpperCase() + words.slice(1)
}

/**
 * Text for one value. Keys ending in `Seconds` are durations by naming convention (the schema has
 * no unit member).
 */
export function optionLabel(field: SettingField, value: number | string | boolean): string {
  if (field.type === 'int') {
    if (field.autoValue !== undefined && value === field.autoValue) return 'Auto'
    return field.key.endsWith('Seconds') ? `${String(value)} seconds` : String(value)
  }
  if (field.type === 'enum' && typeof value === 'string') return humanize(value)
  return String(value)
}

/** A stored setting for read-only views; a missing value shows the schema default. */
export function displayValue(field: SettingField, value: SettingsValue | undefined): string {
  const v = value ?? field.default
  if (field.type === 'bool') return v === true ? 'On' : 'Off'
  if (Array.isArray(v)) return v.join(', ')
  return optionLabel(field, v)
}

/**
 * Fields the generic form edits: not claimed by the game's own panel, not a list (lists need a
 * game-specific picker), and not a bool the server accepts only one value for.
 */
export function editableFields(schema: SettingField[], claimedKeys: string[]): SettingField[] {
  return schema.filter((field) => {
    if (claimedKeys.includes(field.key)) return false
    if (field.type === 'list') return false
    if (field.type === 'bool' && field.options && field.options.length < 2) return false
    return true
  })
}

export function playerCountChoices(game: Pick<GameSummaryDto, 'minPlayers' | 'maxPlayers'>) {
  const { minPlayers, maxPlayers } = game
  return Array.from({ length: Math.max(0, maxPlayers - minPlayers + 1) }, (_, i) => minPlayers + i)
}

/** The server's default room size: 8 clamped to the game's bounds (api-contracts §2.2). */
const SERVER_DEFAULT_MAX_PLAYERS = 8

export function defaultMaxPlayers(game: Pick<GameSummaryDto, 'minPlayers' | 'maxPlayers'>) {
  return Math.max(game.minPlayers, Math.min(SERVER_DEFAULT_MAX_PLAYERS, game.maxPlayers))
}
