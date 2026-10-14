import { CheckCircle2, Clock, Sparkles, UserX } from 'lucide-react'

import type { PlayerDto } from '@/api/types'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { ServerCountdown } from '@/features/session/ServerCountdown'
import type { RoundEndInfo } from '../types'

export interface RoundEndOverlayProps {
  round: number
  totalRounds: number
  word: string | null
  roundEnd: RoundEndInfo | null
  players: PlayerDto[]
  deadlineAt: string | null
}

export function RoundEndOverlay({
  round,
  totalRounds,
  word,
  roundEnd,
  players,
  deadlineAt,
}: RoundEndOverlayProps) {
  const reason = roundEnd?.reason

  const getReasonDetails = () => {
    switch (reason) {
      case 'ALL_GUESSED':
        return {
          icon: <CheckCircle2 className="size-6 text-success" />,
          title: 'All Guessed!',
          description: 'Everyone guessed the secret word in time!',
          color: 'text-success',
        }
      case 'DRAWER_LEFT':
        return {
          icon: <UserX className="size-6 text-destructive" />,
          title: 'Drawer Left',
          description: 'The round ended because the drawer disconnected or left.',
          color: 'text-destructive',
        }
      case 'TIME':
      default:
        return {
          icon: <Clock className="size-6 text-amber-500" />,
          title: "Time's Up!",
          description: 'The drawing timer expired.',
          color: 'text-amber-500',
        }
    }
  }

  const details = getReasonDetails()

  const playerMap = new Map<string, PlayerDto>()
  for (const p of players) {
    playerMap.set(p.guestId, p)
  }

  const scoreDeltas = roundEnd?.scoreDeltas ?? {}
  const winners = Object.entries(scoreDeltas).filter(([, points]) => points > 0)

  return (
    <div className="fixed inset-0 z-40 flex items-center justify-center p-4 bg-background/70 backdrop-blur-xs animate-in fade-in duration-300">
      <Card className="max-w-md w-full shadow-xl border-2 border-primary/30">
        <CardHeader className="text-center pb-2">
          <div className="mx-auto flex size-12 items-center justify-center rounded-2xl bg-primary/10 text-primary mb-2">
            {details.icon}
          </div>
          <span className="text-xs font-bold uppercase tracking-wider text-muted-foreground">
            {details.title}
          </span>
          <CardTitle className="font-fredoka text-2xl sm:text-3xl">
            Round {round} of {totalRounds} Complete!
          </CardTitle>
          <CardDescription className="text-xs sm:text-sm">{details.description}</CardDescription>
        </CardHeader>

        <CardContent className="space-y-4">
          {/* Secret Word Revealed */}
          {word && (
            <div className="flex flex-col items-center justify-center p-3 rounded-xl bg-primary/10 border border-primary/20 text-center">
              <span className="text-xs font-semibold text-muted-foreground uppercase tracking-wider">
                The secret word was
              </span>
              <span className="font-fredoka text-2xl sm:text-3xl font-bold text-primary tracking-wide mt-0.5">
                {word}
              </span>
            </div>
          )}

          {/* Points Awarded This Round */}
          {winners.length > 0 && (
            <div className="space-y-1.5">
              <span className="text-xs font-bold text-muted-foreground uppercase tracking-wider block">
                Points Earned This Round
              </span>
              <div className="grid grid-cols-2 gap-2">
                {winners.map(([guestId, points]) => {
                  const player = playerMap.get(guestId)
                  return (
                    <div
                      key={guestId}
                      className="flex items-center justify-between p-2 rounded-lg bg-muted/40 border border-border/60 text-xs"
                    >
                      <span className="font-bold truncate text-foreground">
                        {player?.nickname ?? 'Player'}
                      </span>
                      <span className="font-bold text-success font-mono">+{points}</span>
                    </div>
                  )
                })}
              </div>
            </div>
          )}

          {/* Next Round Countdown */}
          {deadlineAt && (
            <div className="flex items-center justify-center gap-2 pt-2 border-t border-border/50 text-xs text-muted-foreground">
              <Sparkles className="size-3.5 text-primary" />
              <span>Next round in:</span>
              <ServerCountdown deadlineAt={deadlineAt} size="sm" />
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
