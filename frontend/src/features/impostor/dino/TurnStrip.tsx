import { Pencil, Timer } from 'lucide-react'

import { useServerCountdown } from '@/features/session/useServerCountdown'
import { cn } from '@/lib/utils'

import { playerName } from '../common/copy'
import { InitialBadge } from './InitialBadge'
import { initialsFor } from './initials'

export interface TurnStripProps {
  seatOrder: string[]
  playersById: ReadonlyMap<string, { nickname: string }>
  colors: Record<string, string>
  drawerGuestId: string | null
  currentGuestId: string | null | undefined
  /** 1-based (1..laps). */
  lap: number | null
  laps: number
  /** 0-based. */
  turn: number | null
  totalTurns: number
  deadlineAt: string | null
  /** Players who left are shown faded. */
  leftGuestIds?: ReadonlySet<string>
}

/** Seat order, whose turn it is, the lap/turn counter and the turn clock ([DI-5]…[DI-7]). */
export function TurnStrip({
  seatOrder,
  playersById,
  colors,
  drawerGuestId,
  currentGuestId,
  lap,
  laps,
  turn,
  totalTurns,
  deadlineAt,
  leftGuestIds,
}: TurnStripProps) {
  const { formattedTime, isUrgent, announcement } = useServerCountdown({ deadlineAt })
  const initials = initialsFor(
    seatOrder.map((id) => ({ guestId: id, nickname: playersById.get(id)?.nickname ?? null })),
  )
  const isMyTurn = Boolean(currentGuestId && drawerGuestId === currentGuestId)
  const drawerName = playerName(playersById, drawerGuestId)
  // The text changes with every turn of ours and is empty in between, so it is read once per turn.
  const yourTurn = isMyTurn && turn !== null ? `Your turn — add one stroke. Turn ${turn + 1}.` : ''

  return (
    <section
      aria-label="Turn order"
      className="flex flex-col gap-2 rounded-2xl border border-border bg-card p-3 shadow-xs"
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm font-semibold">
          {lap !== null && turn !== null ? (
            <>
              Lap {lap}/{laps} · Turn {turn + 1}/{totalTurns}
            </>
          ) : (
            'Waiting for the first turn'
          )}
        </p>
        <p
          className={cn(
            'inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-sm font-bold tabular-nums',
            isUrgent ? 'bg-destructive/10 text-destructive' : 'bg-muted text-foreground',
          )}
          aria-label={`Time left this turn: ${formattedTime}`}
        >
          <Timer aria-hidden="true" className="size-4" />
          {formattedTime}
        </p>
      </div>

      <p className="text-sm">
        {isMyTurn ? (
          <strong className="text-primary">Your turn! Add one stroke in your colour.</strong>
        ) : drawerGuestId ? (
          <>
            <strong>{drawerName}</strong> is drawing…
          </>
        ) : null}
      </p>

      <ol className="flex gap-2 overflow-x-auto pb-1" aria-label="Seat order">
        {seatOrder.map((id) => {
          const current = id === drawerGuestId
          const left = leftGuestIds?.has(id) ?? false
          return (
            <li
              key={id}
              aria-current={current ? 'step' : undefined}
              className={cn(
                'flex min-w-[4.5rem] shrink-0 flex-col items-center gap-1 rounded-xl border px-2 py-1.5 text-center',
                current
                  ? 'border-primary bg-primary/10 ring-2 ring-primary/40'
                  : 'border-border/60',
                left && 'opacity-50',
              )}
            >
              <InitialBadge initial={initials.get(id) ?? '?'} color={colors[id]} />
              <span className="max-w-[5.5rem] truncate text-xs font-semibold">
                {id === currentGuestId ? 'You' : playerName(playersById, id)}
              </span>
              {current && (
                <span className="inline-flex items-center gap-0.5 text-[0.65rem] font-bold uppercase text-primary">
                  <Pencil aria-hidden="true" className="size-3" />
                  drawing
                </span>
              )}
              {left && <span className="text-[0.65rem] text-muted-foreground">left</span>}
            </li>
          )
        })}
      </ol>

      <p
        role="status"
        aria-live="assertive"
        className="sr-only"
        data-testid="your-turn-announcement"
      >
        {yourTurn}
      </p>
      <span className="sr-only" aria-live="polite">
        {announcement}
      </span>
    </section>
  )
}
