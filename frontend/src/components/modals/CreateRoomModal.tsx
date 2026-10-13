import { Sparkles } from 'lucide-react'

import type { GameKey } from '@/api/types'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { CreateRoomForm } from '@/features/rooms/CreateRoomForm'

export interface CreateRoomModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  initialGame?: GameKey
}

export function CreateRoomModal({
  open,
  onOpenChange,
  initialGame = 'draw-guess',
}: CreateRoomModalProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <div className="flex items-center gap-2 text-primary">
            <Sparkles className="size-5" aria-hidden />
            <span className="text-xs font-bold uppercase tracking-wider">Host a game</span>
          </div>
          <DialogTitle>Create a Room</DialogTitle>
          <DialogDescription>
            Customize your game settings and invite your friends.
          </DialogDescription>
        </DialogHeader>
        {/* Keyed so reopening starts from the requested game with fresh defaults. */}
        <CreateRoomForm
          key={`${initialGame}-${String(open)}`}
          initialGame={initialGame}
          onCreated={() => onOpenChange(false)}
          onCancel={() => onOpenChange(false)}
        />
      </DialogContent>
    </Dialog>
  )
}
