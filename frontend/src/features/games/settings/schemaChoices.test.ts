import type {
  BoolSettingField,
  EnumSettingField,
  GameDetailDto,
  IntSettingField,
} from '@/api/types'
import { DRAW_GUESS_DETAIL, TELEPHONE_DETAIL } from '@/test/mockApi'

import {
  defaultMaxPlayers,
  displayValue,
  editableFields,
  intChoices,
  optionLabel,
  playerCountChoices,
} from './schemaChoices'

const DG = DRAW_GUESS_DETAIL as unknown as GameDetailDto
const TP = TELEPHONE_DETAIL as unknown as GameDetailDto

const int = (over: Partial<IntSettingField> = {}): IntSettingField => ({
  type: 'int',
  key: 'drawSeconds',
  label: 'Drawing time',
  default: 80,
  min: 30,
  max: 180,
  ...over,
})

describe('intChoices', () => {
  it('offers every value of a small range', () => {
    expect(intChoices(int({ key: 'cycles', min: 1, max: 5, default: 2 }), 2)).toEqual([
      1, 2, 3, 4, 5,
    ])
  })

  it('steps through a large range, always keeping the bounds, default and current value', () => {
    const values = intChoices(int(), 95)
    expect(values[0]).toBe(30)
    expect(values.at(-1)).toBe(180)
    expect(values).toEqual(expect.arrayContaining([80, 95]))
    expect(values.length).toBeLessThanOrEqual(14)
    expect([...values].sort((a, b) => a - b)).toEqual(values)
    expect(values.every((v) => v >= 30 && v <= 180)).toBe(true)
  })

  it('puts the automatic value first', () => {
    const field = int({ key: 'entriesPerChain', min: 4, max: 8, default: 0, autoValue: 0 })
    expect(intChoices(field, 0)).toEqual([0, 4, 5, 6, 7, 8])
  })

  it('does not add an out-of-range current value (the select shows it separately)', () => {
    expect(intChoices(int({ min: 1, max: 3, default: 1 }), 9)).toEqual([1, 2, 3])
  })
})

describe('optionLabel', () => {
  it('names the automatic value and seconds', () => {
    const auto = int({ key: 'entriesPerChain', min: 4, max: 8, default: 0, autoValue: 0 })
    expect(optionLabel(auto, 0)).toBe('Auto')
    expect(optionLabel(auto, 6)).toBe('6')
    expect(optionLabel(int(), 60)).toBe('60 seconds')
  })

  it('humanizes enum values', () => {
    const e: EnumSettingField = {
      type: 'enum',
      key: 'wordSource',
      label: 'Word source',
      default: 'built-in',
      options: ['built-in', 'custom'],
    }
    expect(optionLabel(e, 'built-in')).toBe('Built in')
    expect(optionLabel(e, 'custom')).toBe('Custom')
  })
})

describe('editableFields', () => {
  it('leaves out fields owned by a game panel and unclaimed lists', () => {
    expect(
      editableFields(DG.settingsSchema, ['difficulty', 'wordSource']).map((f) => f.key),
    ).toEqual(['cycles', 'drawSeconds', 'wordChoiceSeconds', 'hintReveal'])
  })

  it('leaves out a bool with a single accepted value', () => {
    expect(editableFields(TP.settingsSchema, []).map((f) => f.key)).toEqual([
      'promptSeconds',
      'drawSeconds',
      'describeSeconds',
      'entriesPerChain',
    ])
    const free: BoolSettingField = { type: 'bool', key: 'x', label: 'X', default: false }
    expect(editableFields([free], [])).toEqual([free])
  })
})

describe('player counts', () => {
  it('lists the server bounds', () => {
    expect(playerCountChoices(TP)).toEqual([4, 5, 6, 7, 8])
  })

  it('defaults to 8 clamped to the bounds (api-contracts §2.2)', () => {
    expect(defaultMaxPlayers({ minPlayers: 2, maxPlayers: 10 })).toBe(8)
    expect(defaultMaxPlayers({ minPlayers: 4, maxPlayers: 8 })).toBe(8)
    expect(defaultMaxPlayers({ minPlayers: 2, maxPlayers: 6 })).toBe(6)
    expect(defaultMaxPlayers({ minPlayers: 9, maxPlayers: 12 })).toBe(9)
  })
})

describe('displayValue', () => {
  it('shows a stored value for read-only summaries', () => {
    const hints: BoolSettingField = {
      type: 'bool',
      key: 'hintReveal',
      label: 'Hints',
      default: true,
    }
    expect(displayValue(hints, true)).toBe('On')
    expect(displayValue(hints, false)).toBe('Off')
    expect(displayValue(int(), 90)).toBe('90 seconds')
    expect(displayValue(int(), undefined)).toBe('80 seconds')
  })
})
