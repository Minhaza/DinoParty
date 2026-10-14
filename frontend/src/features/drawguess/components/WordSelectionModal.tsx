import { Sparkles, Timer } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { ServerCountdown } from '@/features/session/ServerCountdown'

export interface WordSelectionModalProps {
  isDrawer: boolean
  drawerNickname: string
  choices: [string, string, string] | null
  deadlineAt: string | null
  onChooseWord: (index: number) => void
}

export function WordSelectionModal({
  isDrawer,
  drawerNickname,
  choices,
  deadlineAt,
  onChooseWord,
}: WordSelectionModalProps) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-background/80 backdrop-blur-xs animate-in fade-in duration-200">
      <Card className="max-w-md w-full shadow-lg border-2 border-primary/20">
        <CardHeader className="text-center pb-3">
          <div className="mx-auto flex size-12 items-center justify-center rounded-2xl bg-primary/10 text-primary mb-2">
            <Sparkles className="size-6" />
          </div>
          <CardTitle className="font-fredoka text-2xl">
            {isDrawer ? 'Pick Your Word!' : 'Round Starting'}
          </CardTitle>
          <CardDescription>
            {isDrawer
              ? 'Select one word to draw for the other players.'
              : `${drawerNickname} is choosing a word to draw…`}
          </CardDescription>
        </CardHeader>

        <CardContent className="space-y-4">
          {/* Drawer Choices */}
          {isDrawer && choices && choices.length === 3 ? (
            <div className="grid grid-cols-1 gap-2.5">
              {choices.map((word, idx) => (
                <Button
                  key={word}
                  type="button"
                  variant="outline"
                  size="lg"
                  onClick={() => onChooseWord(idx)}
                  className="h-14 font-fredoka text-lg sm:text-xl border-2 border-border/80 hover:border-primary hover:bg-primary/5 hover:text-primary transition-all text-foreground"
                >
                  {word}
                </Button>
              ))}
            </div>
          ) : !isDrawer ? (
            <div className="flex flex-col items-center justify-center py-6 gap-3 text-center">
              <div className="size-8 rounded-full border-2 border-primary border-t-transparent animate-spin" />
              <p className="text-sm font-medium text-muted-foreground">
                Get ready to guess what {drawerNickname} draws!
              </p>
            </div>
          ) : (
            <div className="flex flex-col items-center justify-center py-6 gap-2 text-center text-sm text-muted-foreground">
              <div className="size-6 rounded-full border-2 border-primary border-t-transparent animate-spin" />
              Loading word choices…
            </div>
          )}

          {/* Countdown Footer */}
          {deadlineAt && (
            <div className="flex items-center justify-center gap-2 pt-2 border-t border-border/50 text-xs text-muted-foreground">
              <Timer className="size-3.5" />
              <span>Time to choose:</span>
              <ServerCountdown deadlineAt={deadlineAt} size="sm" />
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
