import type { PlayerDto } from '@/api/types'
import { assetUrl } from '@/assets'
import { ServerCountdown } from '@/features/session/ServerCountdown'

import type { TelephoneKind, TelephoneProgressPayload } from '../types'
import { TelephonePlayerProgress } from './TelephonePlayerProgress'

const SENT_COPY: Record<TelephoneKind, string> = {
  PROMPT: 'Your prompt is on its way!',
  DRAW: 'Your masterpiece is on its way!',
  DESCRIBE: 'Your description is on its way!',
}

export interface TelephoneWaitingScreenProps {
  kind: TelephoneKind
  deadlineAt: string | null
  progress: TelephoneProgressPayload | null
  players: PlayerDto[]
  currentGuestId: string | null | undefined
}

/** Shown once the server has accepted the viewer's entry ([TP-4], [TP-7]): final, nothing to edit. */
export function TelephoneWaitingScreen({
  kind,
  deadlineAt,
  progress,
  players,
  currentGuestId,
}: TelephoneWaitingScreenProps) {
  return (
    <section
      aria-labelledby="telephone-waiting-title"
      className="mx-auto flex w-full max-w-xl flex-col items-center gap-4 rounded-3xl border border-border bg-card px-5 py-8 text-center shadow-xs motion-safe:animate-in motion-safe:fade-in motion-safe:zoom-in-95"
    >
      <img
        src={assetUrl('games/telephone')}
        alt=""
        className="h-32 w-auto max-w-full object-contain sm:h-40"
      />
      <div className="flex flex-col gap-1">
        <h3
          id="telephone-waiting-title"
          role="status"
          className="font-fredoka text-2xl font-semibold text-foreground"
        >
          {SENT_COPY[kind]}
        </h3>
        <p className="text-sm text-muted-foreground">Waiting for the other dinos…</p>
      </div>
      <ServerCountdown deadlineAt={deadlineAt} size="sm" />
      <TelephonePlayerProgress
        progress={progress}
        players={players}
        currentGuestId={currentGuestId}
      />
    </section>
  )
}
