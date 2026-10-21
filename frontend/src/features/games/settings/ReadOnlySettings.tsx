import * as React from 'react'

import type { SettingField, SettingsMap } from '@/api/types'

import { displayValue, editableFields } from './schemaChoices'

/** The room's settings for players who cannot edit them, labelled from the game schema. */
export function ReadOnlySettings({
  schema,
  claimedKeys,
  values,
  maxPlayers,
}: {
  schema: SettingField[]
  claimedKeys: string[]
  values: SettingsMap
  maxPlayers: number
}) {
  return (
    <dl className="grid grid-cols-[1fr_auto] gap-x-4 gap-y-2 text-sm">
      <dt className="font-semibold text-muted-foreground">Max players</dt>
      <dd className="font-bold">{maxPlayers}</dd>
      {editableFields(schema, claimedKeys).map((field) => (
        <React.Fragment key={field.key}>
          <dt className="font-semibold text-muted-foreground">{field.label}</dt>
          <dd className="font-bold">{displayValue(field, values[field.key])}</dd>
        </React.Fragment>
      ))}
    </dl>
  )
}
