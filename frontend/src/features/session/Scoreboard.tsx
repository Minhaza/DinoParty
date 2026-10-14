import { Trophy, Award } from 'lucide-react'

import type { PlayerDto, StandingsEntry } from '@/api/types'
import { DinoAvatar } from '@/components/DinoAvatar'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'

export interface ScoreboardProps {
  standings?: StandingsEntry[] | null
  players: PlayerDto[]
  currentGuestId?: string | null
  className?: string
  title?: string
}

export function Scoreboard({
  standings,
  players,
  currentGuestId,
  className,
  title = 'Standings',
}: ScoreboardProps) {
  // If formal standings are provided (e.g. at end of game or round)
  // map them to player details.
  // Otherwise, sort players by their current score descending.
  const playerMap = new Map(players.map((p) => [p.guestId, p]))

  const rankedItems = standings
    ? standings.map((s) => ({
        guestId: s.guestId,
        rank: s.rank,
        score: s.score,
        nickname: s.nickname || playerMap.get(s.guestId)?.nickname || 'Player',
        avatarKey: playerMap.get(s.guestId)?.avatarKey,
      }))
    : [...players]
        .sort((a, b) => (b.score ?? 0) - (a.score ?? 0))
        .map((p, index) => ({
          guestId: p.guestId,
          rank: index + 1,
          score: p.score ?? 0,
          nickname: p.nickname,
          avatarKey: p.avatarKey,
        }))

  const getRankBadge = (rank: number) => {
    switch (rank) {
      case 1:
        return (
          <span className="flex size-6 items-center justify-center rounded-full bg-amber-500 text-white font-bold text-xs shadow-xs">
            <Trophy className="size-3.5" aria-hidden="true" />
            <span className="sr-only">1st place</span>
          </span>
        )
      case 2:
        return (
          <span className="flex size-6 items-center justify-center rounded-full bg-slate-400 text-white font-bold text-xs shadow-xs">
            <Award className="size-3.5" aria-hidden="true" />
            <span className="sr-only">2nd place</span>
          </span>
        )
      case 3:
        return (
          <span className="flex size-6 items-center justify-center rounded-full bg-amber-700 text-white font-bold text-xs shadow-xs">
            <Award className="size-3.5" aria-hidden="true" />
            <span className="sr-only">3rd place</span>
          </span>
        )
      default:
        return (
          <span className="flex size-6 items-center justify-center rounded-full bg-muted text-muted-foreground font-semibold text-xs">
            {rank}
          </span>
        )
    }
  }

  return (
    <div className={cn('flex flex-col gap-2.5', className)}>
      <div className="flex items-center gap-2 px-1">
        <Trophy className="size-4 text-amber-500" aria-hidden="true" />
        <h3 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
          {title}
        </h3>
      </div>

      <div className="flex flex-col gap-1.5" role="table" aria-label={title}>
        {rankedItems.map((item) => {
          const isYou = item.guestId === currentGuestId
          return (
            <div
              key={item.guestId}
              role="row"
              className={cn(
                'flex items-center justify-between gap-3 rounded-xl border p-2.5 transition-colors',
                isYou
                  ? 'border-primary/50 bg-primary/5 font-medium'
                  : 'border-border/60 bg-card/60',
              )}
            >
              <div className="flex items-center gap-2.5 min-w-0">
                {getRankBadge(item.rank)}
                <DinoAvatar avatarKey={item.avatarKey} size="xs" />
                <span className="truncate text-sm font-semibold text-foreground">
                  {item.nickname}
                </span>
                {isYou && (
                  <Badge variant="outline" className="px-1.5 py-0 text-[10px] leading-4">
                    You
                  </Badge>
                )}
              </div>

              <div className="font-mono text-sm font-bold tabular-nums text-foreground shrink-0">
                {item.score} <span className="text-xs font-normal text-muted-foreground">pts</span>
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}
