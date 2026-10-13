import { useMutation, useQueryClient } from '@tanstack/react-query'
import * as React from 'react'

import { describeError, fieldError } from '@/api/errors'
import { roomQueryKey, updateRoomSettings } from '@/api/rooms'
import type { RoomDto, SettingsMap } from '@/api/types'
import { Button } from '@/components/ui/button'

import { GAME_CONFIGS, maxPlayerChoices } from './gameConfig'
import { SelectField } from './SelectField'

/** Host-only editor for max players and game settings (PATCH /rooms/{code}/settings). */
export function RoomSettingsForm({ room }: { room: RoomDto }) {
  const queryClient = useQueryClient()
  const config = GAME_CONFIGS[room.gameKey]
  const [maxPlayers, setMaxPlayers] = React.useState(room.maxPlayers)
  const [settings, setSettings] = React.useState<SettingsMap>(room.settings)
  const [saved, setSaved] = React.useState(false)

  const mutation = useMutation({
    mutationFn: () => updateRoomSettings(room.code, { maxPlayers, settings }),
    onSuccess: (updated) => {
      queryClient.setQueryData(roomQueryKey(room.code), updated)
      setSaved(true)
    },
  })

  const serverField =
    fieldError(mutation.error, 'maxPlayers') ??
    config.fields.map((f) => fieldError(mutation.error, `settings.${f.key}`)).find(Boolean) ??
    null

  const touch = () => {
    setSaved(false)
    mutation.reset()
  }

  return (
    <form
      aria-label="Room settings"
      className="flex flex-col gap-3"
      onSubmit={(e) => {
        e.preventDefault()
        mutation.mutate()
      }}
    >
      <SelectField
        label="Max players"
        value={maxPlayers}
        onChange={(v) => {
          setMaxPlayers(v)
          touch()
        }}
        options={maxPlayerChoices(room.gameKey).map((n) => ({
          value: n,
          label: `${n} players`,
        }))}
      />
      {config.fields.map((field) => (
        <SelectField
          key={field.key}
          label={field.label}
          value={Number(settings[field.key] ?? field.defaultValue)}
          options={field.choices}
          onChange={(v) => {
            setSettings((s) => ({ ...s, [field.key]: v }))
            touch()
          }}
        />
      ))}
      {mutation.isError && (
        <p role="alert" className="text-sm font-medium text-destructive">
          {serverField ?? describeError(mutation.error)}
        </p>
      )}
      <p aria-live="polite" className="min-h-4 text-sm font-medium text-emerald-600">
        {saved ? 'Settings saved.' : ''}
      </p>
      <Button type="submit" variant="secondary" disabled={mutation.isPending}>
        {mutation.isPending ? 'Saving…' : 'Save settings'}
      </Button>
    </form>
  )
}
