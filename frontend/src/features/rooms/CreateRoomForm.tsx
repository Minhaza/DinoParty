import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Palette, Pencil } from 'lucide-react'
import * as React from 'react'
import { Link, useNavigate } from 'react-router'

import { describeError, fieldError } from '@/api/errors'
import { createRoom, roomQueryKey } from '@/api/rooms'
import type { GameKey, SettingsMap } from '@/api/types'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { ApiError } from '@/api/client'
import { useAuthStore } from '@/stores/authStore'

import { RequireGuest } from '../guest/RequireGuest'
import { defaultSettings, GAME_CONFIGS, maxPlayerChoices } from './gameConfig'
import { SelectField } from './SelectField'

const GAME_ICONS = {
  'draw-guess': { Icon: Palette, tint: 'bg-emerald-500/20 text-emerald-600', ring: 'primary' },
  telephone: { Icon: Pencil, tint: 'bg-orange-500/20 text-orange-600', ring: 'accent' },
} as const

export interface CreateRoomFormProps {
  initialGame?: GameKey
  /** Called after the room exists, right before navigating to its lobby. */
  onCreated?: () => void
  onCancel?: () => void
}

export function CreateRoomForm(props: CreateRoomFormProps) {
  return <RequireGuest>{() => <CreateRoomFields {...props} />}</RequireGuest>
}

function CreateRoomFields({
  initialGame = 'draw-guess',
  onCreated,
  onCancel,
}: CreateRoomFormProps) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const lastRoomCode = useAuthStore((s) => s.lastRoomCode)
  const setLastRoomCode = useAuthStore((s) => s.setLastRoomCode)
  const [game, setGame] = React.useState<GameKey>(initialGame)
  const [maxPlayers, setMaxPlayers] = React.useState(GAME_CONFIGS[initialGame].defaultMaxPlayers)
  const [settings, setSettings] = React.useState<SettingsMap>(defaultSettings(initialGame))

  const config = GAME_CONFIGS[game]

  const chooseGame = (next: GameKey) => {
    setGame(next)
    setMaxPlayers(GAME_CONFIGS[next].defaultMaxPlayers)
    setSettings(defaultSettings(next))
    mutation.reset()
  }

  const mutation = useMutation({
    mutationFn: () => createRoom({ gameKey: game, maxPlayers, settings }),
    onSuccess: (room) => {
      queryClient.setQueryData(roomQueryKey(room.code), room)
      setLastRoomCode(room.code)
      onCreated?.()
      void navigate(`/rooms/${room.code}`)
    },
  })

  const alreadyInRoom =
    mutation.error instanceof ApiError && mutation.error.code === 'ALREADY_IN_ROOM'
  const settingsError =
    fieldError(mutation.error, 'maxPlayers') ??
    config.fields.map((f) => fieldError(mutation.error, `settings.${f.key}`)).find(Boolean) ??
    null

  return (
    <form
      className="flex flex-col gap-6"
      onSubmit={(e) => {
        e.preventDefault()
        mutation.mutate()
      }}
    >
      <fieldset className="flex flex-col gap-2">
        <div className="grid grid-cols-2 gap-2 mt-2">
          {(Object.keys(GAME_CONFIGS) as GameKey[]).map((key) => {
            const { Icon, tint, ring } = GAME_ICONS[key]
            const selected = key === game
            return (
              <button
                key={key}
                type="button"
                aria-pressed={selected}
                onClick={() => chooseGame(key)}
                className={cn(
                  'flex cursor-pointer items-center gap-2.5 rounded-2xl border p-3 text-left outline-none transition-all focus-visible:ring-[3px] focus-visible:ring-ring/50',
                  selected
                    ? ring === 'primary'
                      ? 'border-primary bg-primary/10 font-semibold ring-2 ring-primary/40'
                      : 'border-accent bg-accent/10 font-semibold ring-2 ring-accent/40'
                    : 'border-border/80 bg-background/80 hover:bg-muted/60',
                )}
              >
                <span
                  className={cn(
                    'flex size-8 shrink-0 items-center justify-center rounded-full',
                    tint,
                  )}
                >
                  <Icon className="size-4" aria-hidden />
                </span>
                <span className="text-xs font-bold leading-tight">{GAME_CONFIGS[key].name}</span>
              </button>
            )
          })}
        </div>
      </fieldset>

      <fieldset className="flex flex-col gap-3">
        <legend className="mb-1 text-xs font-bold uppercase tracking-wider text-muted-foreground">
          Room settings
        </legend>
        <SelectField
          label="Max players"
          value={maxPlayers}
          onChange={setMaxPlayers}
          options={maxPlayerChoices(game).map((n) => ({ value: n, label: `${n} players` }))}
        />
        {config.fields.map((field) => (
          <SelectField
            key={field.key}
            label={field.label}
            value={Number(settings[field.key] ?? field.defaultValue)}
            options={field.choices}
            onChange={(value) => setSettings((s) => ({ ...s, [field.key]: value }))}
          />
        ))}
        <p className="text-xs text-muted-foreground">
          Rooms are private: only people with the code or invite link can join.
        </p>
      </fieldset>

      {mutation.isError && (
        <div role="alert" className="text-sm font-medium text-destructive">
          {settingsError ?? describeError(mutation.error)}
          {alreadyInRoom && lastRoomCode && (
            <>
              {' '}
              <Link className="underline" to={`/rooms/${lastRoomCode}`}>
                Go to room {lastRoomCode}
              </Link>
            </>
          )}
        </div>
      )}

      <div className="flex justify-end gap-2">
        {onCancel && (
          <Button type="button" variant="ghost" onClick={onCancel}>
            Cancel
          </Button>
        )}
        <Button type="submit" size="lg" disabled={mutation.isPending}>
          {mutation.isPending ? 'Creating…' : '+ Create room'}
        </Button>
      </div>
    </form>
  )
}
