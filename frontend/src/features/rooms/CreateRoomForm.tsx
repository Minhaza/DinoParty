import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Lock } from 'lucide-react'
import * as React from 'react'
import { Link, useNavigate } from 'react-router'

import { describeError, fieldError } from '@/api/errors'
import { createRoom, roomQueryKey } from '@/api/rooms'
import type { GameKey, SettingsMap } from '@/api/types'
import { GameBanner, PaletteBadge, PencilBadge } from '@/components/art/gameArt'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { ApiError } from '@/api/client'
import { useAuthStore } from '@/stores/authStore'

import { RequireGuest } from '../guest/RequireGuest'
import { defaultSettings, GAME_CONFIGS, maxPlayerChoices } from './gameConfig'
import { SelectField } from './SelectField'

const GAME_BADGES = {
  'draw-guess': {
    Badge: PaletteBadge,
    selected: 'border-game-draw bg-game-draw/10 ring-game-draw/40',
  },
  telephone: {
    Badge: PencilBadge,
    selected: 'border-game-phone bg-game-phone/10 ring-game-phone/40',
  },
} as const

export interface CreateRoomFormProps {
  initialGame?: GameKey
  /** "page" renders settings beside an illustrated room preview; "stacked" suits dialogs. */
  layout?: 'stacked' | 'page'
  /** Called after the room exists, right before navigating to its lobby. */
  onCreated?: () => void
  onCancel?: () => void
}

export function CreateRoomForm(props: CreateRoomFormProps) {
  return <RequireGuest>{() => <CreateRoomFields {...props} />}</RequireGuest>
}

function CreateRoomFields({
  initialGame = 'draw-guess',
  layout = 'stacked',
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

  const isPage = layout === 'page'

  const submitControls = (
    <div className="flex flex-col gap-3">
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

      <div className={cn('flex gap-2', isPage ? 'flex-col-reverse' : 'justify-end')}>
        {onCancel && (
          <Button type="button" variant="ghost" onClick={onCancel}>
            Cancel
          </Button>
        )}
        <Button
          type="submit"
          size="lg"
          disabled={mutation.isPending}
          className={isPage ? 'w-full' : undefined}
        >
          {mutation.isPending ? 'Creating…' : '+ Create room'}
        </Button>
      </div>
    </div>
  )

  const preview = isPage && (
    <aside
      aria-label="Room preview"
      className="flex flex-col gap-4 rounded-[22px] border-2 border-border bg-secondary/70 p-4"
    >
      <h2 className="text-sm font-extrabold uppercase tracking-wider text-muted-foreground">
        Room preview
      </h2>
      <div className="h-44 overflow-hidden rounded-2xl border-2 border-border">
        <GameBanner game={game} />
      </div>
      <div>
        <p className="font-display text-xl font-semibold">{config.name}</p>
        <p className="text-sm font-semibold text-muted-foreground">Up to {maxPlayers} players</p>
      </div>
      <p className="rounded-2xl border-2 border-dashed border-border bg-card px-4 py-3 text-center text-sm font-bold text-muted-foreground">
        Your room code appears once the room is created.
      </p>
      {submitControls}
    </aside>
  )

  return (
    <form
      className={cn(
        'flex flex-col gap-6',
        isPage && 'lg:grid lg:grid-cols-[1fr_340px] lg:items-start',
      )}
      onSubmit={(e) => {
        e.preventDefault()
        mutation.mutate()
      }}
    >
      <div className="flex flex-col gap-6">
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-1 text-xs font-extrabold uppercase tracking-wider text-muted-foreground">
            Game
          </legend>
          <div className="grid grid-cols-2 gap-3">
            {(Object.keys(GAME_CONFIGS) as GameKey[]).map((key) => {
              const { Badge, selected: selectedClass } = GAME_BADGES[key]
              const selected = key === game
              return (
                <button
                  key={key}
                  type="button"
                  aria-pressed={selected}
                  onClick={() => chooseGame(key)}
                  className={cn(
                    'flex cursor-pointer items-center gap-3 rounded-2xl border-2 p-3 text-left outline-none transition-all hover:-translate-y-0.5 focus-visible:ring-[3px] focus-visible:ring-ring/60',
                    selected
                      ? `${selectedClass} font-bold ring-2`
                      : 'border-border bg-card hover:bg-secondary',
                  )}
                >
                  <Badge className="size-9 shrink-0" />
                  <span className="text-sm font-bold leading-tight">{GAME_CONFIGS[key].name}</span>
                </button>
              )
            })}
          </div>
        </fieldset>

        <fieldset className="flex flex-col gap-3">
          <legend className="mb-1 text-xs font-extrabold uppercase tracking-wider text-muted-foreground">
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
          <p className="flex items-center gap-1.5 text-xs font-semibold text-muted-foreground">
            <Lock className="size-3.5" aria-hidden />
            Rooms are private: only people with the code or invite link can join.
          </p>
        </fieldset>
      </div>
      {isPage ? preview : submitControls}
    </form>
  )
}
