import { Eye, Pencil } from 'lucide-react'

import { ServerCountdown } from '@/features/session/ServerCountdown'

export interface WordBannerProps {
  phase: string | null
  isDrawer: boolean
  drawerNickname: string
  word: string | null
  hint: string | null
  deadlineAt: string | null
  isSolved: boolean
}

export function WordBanner({
  phase,
  isDrawer,
  drawerNickname,
  word,
  hint,
  deadlineAt,
  isSolved,
}: WordBannerProps) {
  const isRoundEnd = phase === 'ROUND_END'
  const isDrawing = phase === 'DRAWING'

  return (
    <div className="flex flex-wrap items-center justify-between gap-3 px-4 py-2.5 rounded-xl bg-card border border-border shadow-xs">
      {/* Left: Drawer Info */}
      <div className="flex items-center gap-2">
        <div className="flex size-7 items-center justify-center rounded-lg bg-primary/10 text-primary">
          <Pencil className="size-4" />
        </div>
        <div className="flex flex-col text-left">
          <span className="text-xs text-muted-foreground font-medium">Drawer</span>
          <div className="flex items-center gap-1.5">
            <span className="text-sm font-bold text-foreground leading-tight">
              {isDrawer ? 'You' : drawerNickname || 'Waiting…'}
            </span>
            {isDrawer && (
              <span className="text-xs font-bold text-primary">(Your Turn)</span>
            )}
          </div>
        </div>
      </div>

      {/* Middle: Secret Word or Hint Display */}
      <div className="flex-1 flex items-center justify-center min-w-[200px] px-2 text-center">
        {isRoundEnd && word ? (
          <div className="flex items-center gap-2 animate-in zoom-in-95 duration-200">
            <span className="text-xs text-muted-foreground uppercase tracking-wider font-semibold">
              Word:
            </span>
            <span className="font-fredoka text-xl sm:text-2xl text-primary font-bold tracking-wider">
              {word}
            </span>
          </div>
        ) : isDrawer && word ? (
          <div className="flex items-center gap-2">
            <Eye className="size-4 text-primary" />
            <span className="text-xs text-muted-foreground font-medium">Your word:</span>
            <span className="font-fredoka text-lg sm:text-xl text-primary font-bold tracking-wider">
              {word}
            </span>
            <span className="text-xs text-muted-foreground font-mono">({word.length} letters)</span>
          </div>
        ) : isSolved && word ? (
          <div className="flex items-center gap-2">
            <span className="text-xs text-success font-semibold">You got it!</span>
            <span className="font-fredoka text-lg sm:text-xl text-success font-bold tracking-wider">
              {word}
            </span>
          </div>
        ) : isDrawing && hint ? (
          <div
            className="flex items-center justify-center gap-1.5 flex-wrap font-mono tracking-widest"
            aria-label={`Word hint: ${hint}`}
          >
            {hint.split(' ').map((wordPart, wordIdx) => (
              <span key={wordIdx} className="inline-flex gap-1 mx-1.5">
                {wordPart.split('').map((char, charIdx) => (
                  <span
                    key={charIdx}
                    className={`inline-flex items-center justify-center size-6 sm:size-7 rounded-md font-bold text-base sm:text-lg border ${
                      char === '_'
                        ? 'border-border/80 bg-muted/40 text-transparent'
                        : 'border-primary/40 bg-primary/10 text-primary shadow-xs'
                    }`}
                  >
                    {char === '_' ? ' ' : char.toUpperCase()}
                  </span>
                ))}
              </span>
            ))}
            <span className="text-xs text-muted-foreground font-sans font-medium ml-1">
              ({hint.replace(/ /g, '').length} letters)
            </span>
          </div>
        ) : (
          <span className="text-sm text-muted-foreground italic">Choosing a word…</span>
        )}
      </div>

      {/* Right: Round Countdown */}
      {deadlineAt && (
        <div className="flex items-center gap-2 ml-auto">
          <ServerCountdown deadlineAt={deadlineAt} size="sm" />
        </div>
      )}
    </div>
  )
}
