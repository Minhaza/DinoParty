import { Sparkles } from 'lucide-react'

import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { GuestProfileForm } from '@/features/guest/GuestProfileForm'
import { RequireGuest } from '@/features/guest/RequireGuest'

export interface ProfileModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

export function ProfileModal({ open, onOpenChange }: ProfileModalProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-xl">
        <DialogHeader>
          <div className="flex items-center gap-2 text-primary">
            <Sparkles className="size-5" aria-hidden />
            <span className="text-xs font-bold uppercase tracking-wider">Player profile</span>
          </div>
          <DialogTitle>Choose Your Dino</DialogTitle>
          <DialogDescription>
            Pick the dinosaur that represents you and give it a nickname. Everyone in your room can
            see it.
          </DialogDescription>
        </DialogHeader>
        <RequireGuest>
          {(guest) => (
            <GuestProfileForm
              key={guest.id}
              guest={guest}
              onDone={() => onOpenChange(false)}
              onCancel={() => onOpenChange(false)}
            />
          )}
        </RequireGuest>
      </DialogContent>
    </Dialog>
  )
}
