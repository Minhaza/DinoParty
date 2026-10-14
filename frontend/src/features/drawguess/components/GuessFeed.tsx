import * as React from 'react'
import { CheckCircle2, MessageSquare, Sparkles } from 'lucide-react'

import type { PlayerDto } from '@/api/types'
import { DinoAvatar } from '@/components/DinoAvatar'
import type { DrawGuessChatMessage, GuessFeedbackResult } from '../types'

export interface GuessFeedProps {
  chat: DrawGuessChatMessage[]
  players: PlayerDto[]
  guessedGuestIds: string[]
  currentGuestId: string | null | undefined
  feedback: GuessFeedbackResult | null
  onClearFeedback?: () => void
}

export function GuessFeed({
  chat,
  players,
  guessedGuestIds,
  currentGuestId,
  feedback,
}: GuessFeedProps) {
  const bottomRef = React.useRef<HTMLDivElement | null>(null)

  // Auto-scroll to latest message
  React.useEffect(() => {
    if (typeof bottomRef.current?.scrollIntoView === 'function') {
      bottomRef.current.scrollIntoView({ behavior: 'smooth' })
    }
  }, [chat, feedback, guessedGuestIds.length])

  const playerMap = React.useMemo(() => {
    const map = new Map<string, PlayerDto>()
    for (const p of players) {
      map.set(p.guestId, p)
    }
    return map
  }, [players])

  return (
    <div
      role="log"
      aria-label="Guess and chat feed"
      className="flex flex-col h-full bg-card rounded-xl border border-border overflow-hidden shadow-xs"
    >
      <div className="flex items-center gap-1.5 px-3 py-2 border-b border-border/60 bg-muted/30">
        <MessageSquare className="size-4 text-muted-foreground" />
        <span className="text-xs font-bold uppercase tracking-wider text-muted-foreground">
          Guesses & Chat
        </span>
      </div>

      <div className="flex-1 p-2.5 overflow-y-auto space-y-2 text-xs min-h-[140px] max-h-[320px] sm:max-h-none">
        {chat.length === 0 && !feedback && (
          <div className="flex flex-col items-center justify-center py-6 text-muted-foreground/70 italic text-center">
            <span>No guesses yet!</span>
            <span className="text-[11px] mt-0.5">Type your guess below.</span>
          </div>
        )}

        {chat.map((msg, idx) => {
          const sender = playerMap.get(msg.guestId)
          const isMe = msg.guestId === currentGuestId
          const isSolverChat = msg.scope === 'GUESSERS'

          return (
            <div
              key={`${msg.guestId}-${msg.ts || idx}-${idx}`}
              className={`flex items-start gap-2 p-1.5 rounded-lg transition-colors ${
                isSolverChat
                  ? 'bg-primary/10 border border-primary/20 text-primary-foreground'
                  : 'bg-muted/40 text-foreground'
              }`}
            >
              <DinoAvatar avatarKey={sender?.avatarKey ?? 'rex'} size="xs" />
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-1.5 leading-none mb-0.5">
                  <span
                    className={`font-bold truncate ${
                      isSolverChat ? 'text-primary' : 'text-foreground'
                    }`}
                  >
                    {sender?.nickname ?? 'Unknown'}
                  </span>
                  {isMe && <span className="text-[10px] text-muted-foreground font-semibold">(You)</span>}
                  {isSolverChat && (
                    <span className="inline-flex items-center gap-0.5 px-1 py-0.2 rounded text-[9px] font-bold bg-primary/20 text-primary uppercase">
                      Solver Chat
                    </span>
                  )}
                </div>
                <p className="break-words font-medium text-foreground">{msg.text}</p>
              </div>
            </div>
          )
        })}

        {/* Private Feedback Alert (e.g. CLOSE or DUPLICATE) */}
        {feedback && (
          <div
            role="status"
            className={`flex items-center gap-2 p-2 rounded-lg text-xs font-semibold animate-in slide-in-from-bottom-2 ${
              feedback === 'CLOSE'
                ? 'bg-warning/20 border border-warning/40 text-warning-foreground'
                : 'bg-muted border border-border text-muted-foreground'
            }`}
          >
            <Sparkles className="size-4 shrink-0" />
            <span>
              {feedback === 'CLOSE'
                ? "You're so close! Check your spelling!"
                : 'That word was already guessed this round!'}
            </span>
          </div>
        )}

        {/* Notification of guessed players */}
        {guessedGuestIds.map((guestId) => {
          const solver = playerMap.get(guestId)
          if (!solver) return null
          return (
            <div
              key={`solved-${guestId}`}
              className="flex items-center gap-1.5 p-1.5 rounded-lg bg-success/15 border border-success/30 text-success text-[11px] font-bold animate-in fade-in duration-300"
            >
              <CheckCircle2 className="size-3.5 shrink-0" />
              <span>
                {guestId === currentGuestId ? 'You' : solver.nickname} guessed the secret word!
              </span>
            </div>
          )
        })}

        <div ref={bottomRef} />
      </div>
    </div>
  )
}
