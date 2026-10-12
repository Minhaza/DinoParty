import * as React from 'react'
import { ArrowRight, KeyRound, Sparkles } from 'lucide-react'

import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'

export interface JoinRoomModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

function JoinRoomForm({ onClose }: { onClose: () => void }) {
  const [code, setCode] = React.useState('')
  const [error, setError] = React.useState<string | null>(null)
  const [joinedMessage, setJoinedMessage] = React.useState<string | null>(null)

  const handleJoin = (e: React.FormEvent) => {
    e.preventDefault()
    const cleanCode = code.trim().toUpperCase()
    if (!cleanCode) {
      setError('Please enter a room code.')
      return
    }
    if (cleanCode.length < 4 || cleanCode.length > 8) {
      setError('Room code should be 4 to 8 characters.')
      return
    }

    setJoinedMessage(`Joining room ${cleanCode}... (Multiplayer socket connects in Phase 3)`)
  }

  return (
    <>
      <DialogHeader>
        <div className="flex items-center gap-2 text-primary">
          <KeyRound className="size-5" />
          <span className="text-xs font-bold uppercase tracking-wider">Party Join</span>
        </div>
        <DialogTitle>Join a Room</DialogTitle>
        <DialogDescription>
          Enter the room code shared by your host to join the party!
        </DialogDescription>
      </DialogHeader>

      {joinedMessage ? (
        <div className="flex flex-col items-center gap-4 py-6 text-center">
          <div className="flex size-14 items-center justify-center rounded-full bg-emerald-500/15 text-emerald-600">
            <Sparkles className="size-7" />
          </div>
          <p className="font-bold text-foreground">{joinedMessage}</p>
          <Button onClick={onClose} className="mt-2">
            Close Preview
          </Button>
        </div>
      ) : (
        <form onSubmit={handleJoin} className="flex flex-col gap-4 py-3">
          <div className="flex flex-col gap-2">
            <label
              htmlFor="join-code-input"
              className="text-xs font-bold uppercase tracking-wider text-muted-foreground"
            >
              Room Code
            </label>
            <div className="relative">
              <Input
                id="join-code-input"
                value={code}
                onChange={(e) => {
                  setCode(e.target.value.toUpperCase())
                  if (error) setError(null)
                }}
                placeholder="e.g. AB3D7"
                maxLength={8}
                className="h-14 text-center font-mono text-2xl font-black uppercase tracking-widest"
                autoFocus
              />
            </div>
            {error && <p className="text-xs font-medium text-destructive">{error}</p>}
          </div>

          <div className="flex items-center justify-between rounded-2xl bg-secondary/50 p-3 text-xs text-muted-foreground">
            <span>Have an invite link?</span>
            <span className="font-semibold text-foreground">Just paste it into your browser</span>
          </div>

          <Button type="submit" size="lg" className="w-full text-base font-bold mt-2">
            <span>Join Room</span>
            <ArrowRight className="size-4 stroke-[2.5]" />
          </Button>
        </form>
      )}

      <DialogFooter>
        {!joinedMessage && (
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
        )}
      </DialogFooter>
    </>
  )
}

export function JoinRoomModal({ open, onOpenChange }: JoinRoomModalProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md">
        <JoinRoomForm key={String(open)} onClose={() => onOpenChange(false)} />
      </DialogContent>
    </Dialog>
  )
}
