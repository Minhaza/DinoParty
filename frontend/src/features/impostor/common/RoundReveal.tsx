import * as React from 'react'
import { Drama } from 'lucide-react'

import { DinoAvatar } from '@/components/DinoAvatar'
import { ServerCountdown } from '@/features/session/ServerCountdown'
import { cn } from '@/lib/utils'

import { OUTCOME_COPY, playerName } from './copy'
import type { ImpostorRoundEndedPayload } from './types'

export interface RoundRevealPlayer {
  guestId: string
  nickname: string
  avatarKey?: string | null
}

export interface RoundRevealProps {
  roundEnd: ImpostorRoundEndedPayload
  /** Players in display order (seat order of the round); players who left are named generically. */
  players: RoundRevealPlayer[]
  currentGuestId: string | null | undefined
  deadlineAt: string | null
  totalRounds?: number
}

/** `ROUND_END` ([DI-14]): who the impostor was, the word, every vote, the guess and the points. */
export function RoundReveal({
  roundEnd,
  players,
  currentGuestId,
  deadlineAt,
  totalRounds,
}: RoundRevealProps) {
  const titleId = React.useId()
  const byId = new Map(players.map((p) => [p.guestId, p]))
  const name = (id: string | null | undefined) =>
    id && id === currentGuestId ? 'You' : playerName(byId, id)
  const copy = OUTCOME_COPY[roundEnd.outcome]
  const impostor = byId.get(roundEnd.impostorGuestId)

  // Everyone who voted or scored, in display order, then anyone the roster no longer knows.
  const ids = [
    ...players.map((p) => p.guestId),
    ...[...Object.keys(roundEnd.scoreDeltas), ...Object.keys(roundEnd.votes)].filter(
      (id) => !byId.has(id),
    ),
  ].filter((id, i, all) => all.indexOf(id) === i)

  return (
    <section
      aria-labelledby={titleId}
      className="flex flex-col gap-4 rounded-2xl border border-border bg-card p-5 shadow-xs animate-in fade-in duration-300 motion-reduce:animate-none"
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            Round {roundEnd.round}
            {totalRounds ? ` of ${totalRounds}` : ''}
          </p>
          <h2 id={titleId} className="font-fredoka text-2xl font-semibold">
            {copy.headline}
          </h2>
          <p className="text-sm text-muted-foreground">{copy.detail}</p>
        </div>
        <ServerCountdown deadlineAt={deadlineAt} size="sm" />
      </div>

      <p aria-live="polite" className="sr-only">
        {`${copy.headline} The impostor was ${name(roundEnd.impostorGuestId)}. The word was ${roundEnd.word}.`}
      </p>

      <dl className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <div className="flex items-center gap-3 rounded-xl bg-muted/50 px-3 py-2">
          <DinoAvatar avatarKey={impostor?.avatarKey} size="sm" alt="" />
          <div>
            <dt className="flex items-center gap-1 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
              <Drama aria-hidden="true" className="size-3.5" />
              Impostor
            </dt>
            <dd className="font-bold">{name(roundEnd.impostorGuestId)}</dd>
          </div>
        </div>
        <div className="rounded-xl bg-muted/50 px-3 py-2">
          <dt className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            Category
          </dt>
          <dd className="font-bold">{roundEnd.category.label}</dd>
        </div>
        <div className="rounded-xl bg-muted/50 px-3 py-2">
          <dt className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            Secret word
          </dt>
          <dd className="font-bold">{roundEnd.word}</dd>
        </div>
        {roundEnd.decoyWord !== null && (
          <div className="rounded-xl bg-muted/50 px-3 py-2 sm:col-span-3">
            <dt className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
              The impostor&apos;s decoy word
            </dt>
            <dd className="font-bold">{roundEnd.decoyWord}</dd>
          </div>
        )}
        <div className="rounded-xl bg-muted/50 px-3 py-2 sm:col-span-3">
          <dt className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            Impostor&apos;s guess
          </dt>
          <dd className="font-bold">{roundEnd.guess ?? 'No guess'}</dd>
        </div>
      </dl>

      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <caption className="sr-only">Votes and points this round</caption>
          <thead>
            <tr className="text-left text-xs uppercase tracking-wide text-muted-foreground">
              <th scope="col" className="py-1 pr-2 font-semibold">
                Player
              </th>
              <th scope="col" className="py-1 pr-2 font-semibold">
                Voted for
              </th>
              <th scope="col" className="py-1 pr-2 text-right font-semibold">
                Points
              </th>
              <th scope="col" className="py-1 text-right font-semibold">
                Total
              </th>
            </tr>
          </thead>
          <tbody>
            {ids.map((id) => {
              const target = roundEnd.votes[id]
              const delta = roundEnd.scoreDeltas[id] ?? 0
              return (
                <tr
                  key={id}
                  className={cn(
                    'border-t border-border/60',
                    id === roundEnd.impostorGuestId && 'font-semibold',
                  )}
                >
                  <th scope="row" className="py-1.5 pr-2 text-left font-medium">
                    {name(id)}
                    {id === roundEnd.impostorGuestId && (
                      <span className="ml-1 text-xs text-muted-foreground">(impostor)</span>
                    )}
                  </th>
                  <td className="py-1.5 pr-2">
                    {target ? (
                      <>
                        {name(target)}
                        {target === roundEnd.impostorGuestId && (
                          <span className="ml-1 text-xs text-primary">✓ right</span>
                        )}
                      </>
                    ) : (
                      <span className="text-muted-foreground">No vote</span>
                    )}
                  </td>
                  <td className="py-1.5 pr-2 text-right tabular-nums">
                    {delta > 0 ? `+${delta}` : delta}
                  </td>
                  <td className="py-1.5 text-right tabular-nums">{roundEnd.scores[id] ?? 0}</td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </section>
  )
}
