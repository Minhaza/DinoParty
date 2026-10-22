import { playerName } from '../common/copy'
import { InitialBadge } from './InitialBadge'
import { initialsFor } from './initials'

export interface StrokeLegendProps {
  seatOrder: string[]
  playersById: ReadonlyMap<string, { nickname: string }>
  colors: Record<string, string>
  currentGuestId?: string | null
}

/** Who drew in which colour: colour, initial and name together, never colour alone (D56). */
export function StrokeLegend({
  seatOrder,
  playersById,
  colors,
  currentGuestId,
}: StrokeLegendProps) {
  const initials = initialsFor(
    seatOrder.map((id) => ({ guestId: id, nickname: playersById.get(id)?.nickname ?? null })),
  )
  if (seatOrder.length === 0) return null
  return (
    <section aria-label="Stroke colours" className="rounded-2xl border border-border bg-card p-3">
      <h2 className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
        Who drew what
      </h2>
      <ul className="grid grid-cols-2 gap-x-3 gap-y-1.5 sm:grid-cols-3 lg:grid-cols-2">
        {seatOrder.map((id) => (
          <li key={id} className="flex min-w-0 items-center gap-2 text-sm">
            <InitialBadge
              initial={initials.get(id) ?? '?'}
              color={colors[id]}
              className="size-6 border-2 text-[0.6rem]"
            />
            <span className="truncate">
              {id === currentGuestId ? 'You' : playerName(playersById, id)}
            </span>
          </li>
        ))}
      </ul>
    </section>
  )
}
