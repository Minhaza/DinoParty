import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Lock } from 'lucide-react'
import * as React from 'react'
import { Link, useNavigate } from 'react-router'

import { describeError, fieldError, otherFieldErrors } from '@/api/errors'
import { createRoom, roomQueryKey } from '@/api/rooms'
import type { CreateRoomInput, GameDetailDto, GameKey, SettingsMap } from '@/api/types'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { ApiError } from '@/api/client'
import { GameBanner } from '@/features/games/GameBanner'
import { LoadError } from '@/features/games/LoadError'
import { gameModule, gameVisual, TONE_STYLES } from '@/features/games/gameModules'
import {
  editableFields,
  defaultMaxPlayers,
  playerCountChoices,
} from '@/features/games/settings/schemaChoices'
import { SettingsFields } from '@/features/games/settings/SettingsFields'
import { prefetchGameDetail, useEnabledGames, useGameDetail } from '@/features/games/useGames'
import { useAuthStore } from '@/stores/authStore'

import { RequireGuest } from '../guest/RequireGuest'
import { SelectField } from './SelectField'

export interface CreateRoomFormProps {
  /** Preselected game; ignored when it is not enabled or this client cannot play it. */
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

/** The host's edits for one game; dropped when another game is picked. */
type Draft = CreateRoomInput & { maxPlayers: number; settings: SettingsMap }

function defaultsOf(detail: GameDetailDto): Draft {
  return {
    gameKey: detail.key,
    maxPlayers: defaultMaxPlayers(detail),
    settings: { ...detail.defaultSettings },
  }
}

const legendClass = 'mb-1 text-xs font-extrabold uppercase tracking-wider text-muted-foreground'

function CreateRoomFields({
  initialGame,
  layout = 'stacked',
  onCreated,
  onCancel,
}: CreateRoomFormProps) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const lastRoomCode = useAuthStore((s) => s.lastRoomCode)
  const setLastRoomCode = useAuthStore((s) => s.setLastRoomCode)

  const games = useEnabledGames()
  // A game the server offers but this client has no screens for cannot be hosted from here.
  const choices = (games.data ?? []).filter((g) => gameModule(g.key))
  const [picked, setPicked] = React.useState<GameKey | undefined>(initialGame)
  const game = choices.find((g) => g.key === picked) ?? choices[0] ?? null

  const detailQuery = useGameDetail(game?.key)
  const detail = detailQuery.data?.key === game?.key ? detailQuery.data : undefined
  const [draft, setDraft] = React.useState<Draft | null>(null)
  const form = detail ? (draft?.gameKey === detail.key ? draft : defaultsOf(detail)) : null

  const mutation = useMutation({
    mutationFn: (input: Draft) => createRoom(input),
    onSuccess: (room) => {
      queryClient.setQueryData(roomQueryKey(room.code), room)
      setLastRoomCode(room.code)
      onCreated?.()
      void navigate(`/rooms/${room.code}`)
    },
  })

  const chooseGame = (next: GameKey) => {
    setPicked(next)
    setDraft(null)
    mutation.reset()
  }

  const edit = (change: Partial<Draft>) => {
    if (form) setDraft({ ...form, ...change })
  }

  const claimedKeys = gameModule(game?.key ?? '')?.lobbyPanelFields ?? []
  const shownFields = new Set([
    'maxPlayers',
    ...(detail ? editableFields(detail.settingsSchema, claimedKeys) : []).map(
      (f) => `settings.${f.key}`,
    ),
  ])
  const unshownErrors = otherFieldErrors(mutation.error, shownFields)

  const alreadyInRoom =
    mutation.error instanceof ApiError && mutation.error.code === 'ALREADY_IN_ROOM'

  const isPage = layout === 'page'

  const submitControls = (
    <div className="flex flex-col gap-3">
      {mutation.isError && (
        <div role="alert" className="text-sm font-medium text-destructive">
          {unshownErrors.length > 0 ? unshownErrors.join(' ') : describeError(mutation.error)}
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
          disabled={!form || mutation.isPending}
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
        {game ? (
          <GameBanner iconKey={game.iconKey} />
        ) : (
          <div className="size-full animate-pulse bg-muted motion-reduce:animate-none" />
        )}
      </div>
      {game && (
        <div>
          <p className="font-display text-xl font-semibold">{game.name}</p>
          {form && (
            <p className="text-sm font-semibold text-muted-foreground">
              Up to {form.maxPlayers} players
            </p>
          )}
        </div>
      )}
      <p className="rounded-2xl border-2 border-dashed border-border bg-card px-4 py-3 text-center text-sm font-bold text-muted-foreground">
        Your room code appears once the room is created.
      </p>
      {submitControls}
    </aside>
  )

  let gamePicker: React.ReactNode
  if (games.isPending) {
    gamePicker = (
      <div className="grid grid-cols-2 gap-3" aria-busy="true" aria-label="Loading games">
        {[0, 1].map((i) => (
          <div
            key={i}
            className="h-[68px] animate-pulse rounded-2xl bg-secondary motion-reduce:animate-none"
          />
        ))}
      </div>
    )
  } else if (games.isError) {
    gamePicker = <LoadError error={games.error} onRetry={() => void games.refetch()} />
  } else if (choices.length === 0) {
    gamePicker = (
      <p role="status" className="text-sm font-semibold text-muted-foreground">
        No games are available right now. Please check back soon.
      </p>
    )
  } else {
    gamePicker = (
      <div className="grid grid-cols-2 gap-3">
        {choices.map((g) => {
          const { Badge, tone } = gameVisual(g.iconKey)
          const selected = g.key === game?.key
          return (
            <button
              key={g.key}
              type="button"
              aria-pressed={selected}
              onClick={() => chooseGame(g.key)}
              onPointerEnter={() => void prefetchGameDetail(queryClient, g.key)}
              onFocus={() => void prefetchGameDetail(queryClient, g.key)}
              className={cn(
                'flex cursor-pointer items-center gap-3 rounded-2xl border-2 p-3 text-left outline-none transition-all hover:-translate-y-0.5 focus-visible:ring-[3px] focus-visible:ring-ring/60 motion-reduce:hover:translate-y-0',
                selected
                  ? `${TONE_STYLES[tone].selected} font-bold ring-2`
                  : 'border-border bg-card hover:bg-secondary',
              )}
            >
              <Badge className="size-9 shrink-0" />
              <span className="flex min-w-0 flex-col">
                <span className="text-sm font-bold leading-tight">{g.name}</span>
                <span className="text-xs font-semibold text-muted-foreground">
                  {g.minPlayers}–{g.maxPlayers} players
                </span>
              </span>
            </button>
          )
        })}
      </div>
    )
  }

  let settingsBody: React.ReactNode = null
  if (detailQuery.isError && !detail) {
    settingsBody = (
      <LoadError error={detailQuery.error} onRetry={() => void detailQuery.refetch()} />
    )
  } else if (detail && form) {
    settingsBody = (
      <>
        <SelectField
          label="Max players"
          value={form.maxPlayers}
          onChange={(maxPlayers) => edit({ maxPlayers })}
          options={playerCountChoices(detail).map((n) => ({ value: n, label: `${n} players` }))}
          error={fieldError(mutation.error, 'maxPlayers')}
        />
        <SettingsFields
          schema={detail.settingsSchema}
          claimedKeys={claimedKeys}
          values={form.settings}
          onChange={(key, value) => edit({ settings: { ...form.settings, [key]: value } })}
          errorFor={(key) => fieldError(mutation.error, `settings.${key}`)}
        />
      </>
    )
  } else if (game) {
    settingsBody = (
      <div className="flex flex-col gap-3" aria-busy="true" aria-label="Loading settings">
        {[0, 1, 2].map((i) => (
          <div
            key={i}
            className="h-10 animate-pulse rounded-xl bg-secondary motion-reduce:animate-none"
          />
        ))}
      </div>
    )
  }

  return (
    <form
      className={cn(
        'flex flex-col gap-6',
        isPage && 'lg:grid lg:grid-cols-[1fr_340px] lg:items-start',
      )}
      onSubmit={(e) => {
        e.preventDefault()
        if (form) mutation.mutate(form)
      }}
    >
      <div className="flex flex-col gap-6">
        <fieldset className="flex flex-col gap-2">
          <legend className={legendClass}>Game</legend>
          {gamePicker}
        </fieldset>

        {game && (
          <fieldset className="flex flex-col gap-3">
            <legend className={legendClass}>Room settings</legend>
            {settingsBody}
            <p className="flex items-center gap-1.5 text-xs font-semibold text-muted-foreground">
              <Lock className="size-3.5" aria-hidden />
              Rooms are private: only people with the code or invite link can join.
            </p>
          </fieldset>
        )}
      </div>
      {isPage ? preview : submitControls}
    </form>
  )
}
