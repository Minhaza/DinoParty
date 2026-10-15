import type { PlayerDto } from '@/api/types'
import { DinoAvatar } from '@/components/DinoAvatar'

import type { TelephoneProgressPayload } from '../types'

export interface TelephonePlayerProgressProps {
  progress: TelephoneProgressPayload | null
  players: PlayerDto[]
  currentGuestId: string | null | undefined
}

/** Public step progress ([TP-4]): who has submitted, by name only — never what or for whom. */
export function TelephonePlayerProgress({
  progress,
  players,
  currentGuestId,
}: TelephonePlayerProgressProps) {
  if (!progress) return null
  const done = progress.submittedGuestIds
    .map((id) => players.find((p) => p.guestId === id))
    .filter((p): p is PlayerDto => p !== undefined)

  return (
    <section aria-label="Step progress" className="flex flex-col items-center gap-3">
      <p aria-live="polite" className="text-sm font-semibold text-foreground">
        {progress.submittedCount} of {progress.total} dinos done
      </p>
      {done.length > 0 && (
        <ul className="flex flex-wrap justify-center gap-2" aria-label="Players who have submitted">
          {done.map((p) => (
            <li
              key={p.guestId}
              className="flex items-center gap-1.5 rounded-full border border-border bg-background py-1 pr-3 pl-1 text-xs font-medium text-foreground"
            >
              <DinoAvatar avatarKey={p.avatarKey} size="xs" aria-hidden="true" />
              <span className="max-w-[10rem] truncate">
                {p.guestId === currentGuestId ? 'You' : p.nickname}
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
