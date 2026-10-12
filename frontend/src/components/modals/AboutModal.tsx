import { Heart, Info, ShieldCheck, Zap } from 'lucide-react'

import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'

export interface AboutModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

export function AboutModal({ open, onOpenChange }: AboutModalProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <div className="flex items-center gap-2 text-primary">
            <Info className="size-5" />
            <span className="text-xs font-bold uppercase tracking-wider">About DinoParty</span>
          </div>
          <DialogTitle>Playful Social Gaming</DialogTitle>
          <DialogDescription>
            An original, fast, and friendly multiplayer game platform for everyone.
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-4 py-2">
          <div className="flex items-start gap-3 rounded-2xl bg-secondary/40 p-3.5">
            <ShieldCheck className="size-5 text-emerald-600 shrink-0 mt-0.5" />
            <div>
              <p className="font-semibold text-sm text-foreground">Zero Friction</p>
              <p className="text-xs text-muted-foreground mt-0.5">
                No sign-up forms, passwords, or personal data collected. Just pick a nickname and
                start playing.
              </p>
            </div>
          </div>

          <div className="flex items-start gap-3 rounded-2xl bg-secondary/40 p-3.5">
            <Zap className="size-5 text-amber-600 shrink-0 mt-0.5" />
            <div>
              <p className="font-semibold text-sm text-foreground">Real-Time WebSocket Engine</p>
              <p className="text-xs text-muted-foreground mt-0.5">
                Low-latency drawing synchronization with responsive canvas scaling across devices.
              </p>
            </div>
          </div>

          <div className="flex items-start gap-3 rounded-2xl bg-secondary/40 p-3.5">
            <Heart className="size-5 text-rose-500 shrink-0 mt-0.5" />
            <div>
              <p className="font-semibold text-sm text-foreground">Good Games, Good Friends</p>
              <p className="text-xs text-muted-foreground mt-0.5">
                Crafted with care to bring laughter and creativity to game nights, remote teams, and
                hangouts.
              </p>
            </div>
          </div>
        </div>

        <DialogFooter>
          <Button onClick={() => onOpenChange(false)}>Close</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
