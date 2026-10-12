import { Gamepad2, Sparkles } from 'lucide-react'

import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'

export interface HowToPlayModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

export function HowToPlayModal({ open, onOpenChange }: HowToPlayModalProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-xl">
        <DialogHeader>
          <div className="flex items-center gap-2 text-primary">
            <Gamepad2 className="size-5" />
            <span className="text-xs font-bold uppercase tracking-wider">Party Guide</span>
          </div>
          <DialogTitle>How to Play DinoParty</DialogTitle>
          <DialogDescription>
            Fast, fun browser party games — no account, no download, just good times.
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-4 py-2">
          {/* Step 1 */}
          <div className="flex items-start gap-4 rounded-2xl border border-border/80 bg-secondary/30 p-4">
            <div className="flex size-10 shrink-0 items-center justify-center rounded-2xl bg-emerald-500/15 text-emerald-600 font-bold">
              1
            </div>
            <div className="flex flex-col">
              <h4 className="font-bold text-foreground">Create or Join a Room</h4>
              <p className="text-sm text-muted-foreground leading-relaxed mt-0.5">
                Hit <span className="font-semibold text-foreground">+ Create a Room</span> or enter
                a 5-character room code from your friends. Pick your favorite dino avatar and
                nickname!
              </p>
            </div>
          </div>

          {/* Step 2 */}
          <div className="flex items-start gap-4 rounded-2xl border border-border/80 bg-secondary/30 p-4">
            <div className="flex size-10 shrink-0 items-center justify-center rounded-2xl bg-orange-500/15 text-orange-600 font-bold">
              2
            </div>
            <div className="flex flex-col">
              <h4 className="font-bold text-foreground">Draw, Guess, & Create</h4>
              <p className="text-sm text-muted-foreground leading-relaxed mt-0.5">
                In <span className="font-semibold text-foreground">Draw & Guess</span>, take turns
                drawing secret words on the interactive canvas while teammates guess in real time.
                In <span className="font-semibold text-foreground">Dino Telephone</span>, alternate
                drawings and descriptions to create hilarious chains!
              </p>
            </div>
          </div>

          {/* Step 3 */}
          <div className="flex items-start gap-4 rounded-2xl border border-border/80 bg-secondary/30 p-4">
            <div className="flex size-10 shrink-0 items-center justify-center rounded-2xl bg-purple-500/15 text-purple-600 font-bold">
              3
            </div>
            <div className="flex flex-col">
              <h4 className="font-bold text-foreground">Reveal the Album & Celebrate</h4>
              <p className="text-sm text-muted-foreground leading-relaxed mt-0.5">
                Laugh together at unexpected artistic masterpieces during the final reveal. Climb
                the scoreboard or hit "Play Again" for another round!
              </p>
            </div>
          </div>

          {/* Tips pill */}
          <div className="flex items-center gap-2 rounded-2xl bg-primary/10 px-4 py-3 text-xs text-primary font-medium">
            <Sparkles className="size-4 shrink-0" />
            <span>Works smoothly on desktops, laptops, tablets, and phones!</span>
          </div>
        </div>

        <DialogFooter>
          <Button onClick={() => onOpenChange(false)}>Got it, let's play!</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
