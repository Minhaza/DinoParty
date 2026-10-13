import { KeyRound } from 'lucide-react'

import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { JoinRoomForm } from '@/features/rooms/JoinRoomForm'

export interface JoinRoomModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

export function JoinRoomModal({ open, onOpenChange }: JoinRoomModalProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <div className="flex items-center gap-2 text-primary">
            <KeyRound className="size-5" aria-hidden />
            <span className="text-xs font-bold uppercase tracking-wider">Party join</span>
          </div>
          <DialogTitle>Join a Room</DialogTitle>
          <DialogDescription>
            Enter the room code shared by your host to join the party!
          </DialogDescription>
        </DialogHeader>
        <JoinRoomForm
          key={String(open)}
          onJoined={() => onOpenChange(false)}
          onCancel={() => onOpenChange(false)}
        />
      </DialogContent>
    </Dialog>
  )
}
