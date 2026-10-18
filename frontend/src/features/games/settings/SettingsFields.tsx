import { useId } from 'react'

import type { BoolSettingField, SettingField, SettingsMap, SettingsValue } from '@/api/types'
import { SelectField } from '@/features/rooms/SelectField'

import { editableFields, intChoices, optionLabel } from './schemaChoices'

export interface SettingsFieldsProps {
  /** The game's `settingsSchema` (GET /games/{key}), in display order. */
  schema: SettingField[]
  /** Keys a game-specific panel edits instead (features/games/gameModules). */
  claimedKeys: string[]
  values: SettingsMap
  onChange: (key: string, value: SettingsValue) => void
  /** Server message for `settings.<key>`, if the last request rejected it. */
  errorFor: (key: string) => string | null
  disabled?: boolean
}

/** Host settings rendered from the server schema: int and enum as selects, bool as a checkbox. */
export function SettingsFields({
  schema,
  claimedKeys,
  values,
  onChange,
  errorFor,
  disabled,
}: SettingsFieldsProps) {
  return (
    <>
      {editableFields(schema, claimedKeys).map((field) => {
        const error = errorFor(field.key)
        switch (field.type) {
          case 'int': {
            const raw = values[field.key]
            const current = typeof raw === 'number' ? raw : field.default
            return (
              <SelectField
                key={field.key}
                label={field.label}
                value={current}
                options={intChoices(field, current).map((v) => ({
                  value: v,
                  label: optionLabel(field, v),
                }))}
                onChange={(v) => onChange(field.key, v)}
                disabled={disabled}
                error={error}
              />
            )
          }
          case 'enum': {
            const raw = values[field.key]
            return (
              <SelectField
                key={field.key}
                label={field.label}
                value={typeof raw === 'string' ? raw : field.default}
                options={field.options.map((v) => ({ value: v, label: optionLabel(field, v) }))}
                onChange={(v) => onChange(field.key, v)}
                disabled={disabled}
                error={error}
              />
            )
          }
          case 'bool':
            return (
              <BoolRow
                key={field.key}
                field={field}
                checked={
                  typeof values[field.key] === 'boolean'
                    ? Boolean(values[field.key])
                    : field.default
                }
                onChange={(v) => onChange(field.key, v)}
                disabled={disabled}
                error={error}
              />
            )
          default:
            return null
        }
      })}
    </>
  )
}

function BoolRow({
  field,
  checked,
  onChange,
  disabled,
  error,
}: {
  field: BoolSettingField
  checked: boolean
  onChange: (value: boolean) => void
  disabled?: boolean
  error: string | null
}) {
  const id = useId()
  const errorId = `${id}-error`
  return (
    <div className="flex flex-col gap-1">
      <div className="flex min-h-10 items-center justify-between gap-4">
        <label htmlFor={id} className="text-sm font-medium text-foreground">
          {field.label}
        </label>
        <input
          id={id}
          type="checkbox"
          checked={checked}
          disabled={disabled}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? errorId : undefined}
          onChange={(e) => onChange(e.target.checked)}
          className="size-5 cursor-pointer rounded-md accent-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary disabled:opacity-60"
        />
      </div>
      {error && (
        <p id={errorId} className="text-right text-xs font-semibold text-destructive">
          {error}
        </p>
      )}
    </div>
  )
}
