import * as React from 'react'
import { Check, Sparkles } from 'lucide-react'

import { DinoAvatar } from '@/components/DinoAvatar'
import { DINO_AVATARS, type DinoAvatarKey } from '@/components/dinoAvatars'
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
import { cn } from '@/lib/utils'
import { useGuestStore } from '@/stores/guestStore'

export interface ProfileModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

function ProfileForm({ onClose }: { onClose: () => void }) {
  const currentNickname = useGuestStore((s) => s.nickname)
  const currentAvatarKey = useGuestStore((s) => s.avatarKey)
  const setProfile = useGuestStore((s) => s.setProfile)

  const [nickname, setNickname] = React.useState(currentNickname)
  const [avatarKey, setAvatarKey] = React.useState<DinoAvatarKey>(currentAvatarKey)
  const [error, setError] = React.useState<string | null>(null)

  const handleSave = () => {
    const trimmed = nickname.trim()
    if (trimmed.length < 2) {
      setError('Nickname must be at least 2 characters.')
      return
    }
    if (trimmed.length > 20) {
      setError('Nickname must be 20 characters or fewer.')
      return
    }
    setProfile({ nickname: trimmed, avatarKey })
    onClose()
  }

  return (
    <>
      <DialogHeader>
        <div className="flex items-center gap-2 text-primary">
          <Sparkles className="size-5" />
          <span className="text-xs font-bold uppercase tracking-wider">Player Profile</span>
        </div>
        <DialogTitle>Customize Your Dino</DialogTitle>
        <DialogDescription>
          Choose your dinosaur archetype and party nickname. Visible to all players in your room.
        </DialogDescription>
      </DialogHeader>

      <div className="flex flex-col gap-6 py-2">
        {/* Avatar Preview */}
        <div className="flex flex-col items-center justify-center gap-2 rounded-2xl bg-secondary/50 p-4">
          <DinoAvatar
            avatarKey={avatarKey}
            size="xl"
            className="shadow-md transition-all hover:scale-105"
          />
          <div className="text-center">
            <p className="font-bold text-foreground text-lg">{nickname || 'Your Nickname'}</p>
            <p className="text-xs text-muted-foreground">
              {DINO_AVATARS.find((d) => d.key === avatarKey)?.label}
            </p>
          </div>
        </div>

        {/* Dino Selector */}
        <div className="flex flex-col gap-2">
          <label className="text-xs font-bold uppercase tracking-wider text-muted-foreground">
            Select Dinosaur
          </label>
          <div className="grid grid-cols-3 gap-2.5 sm:grid-cols-6">
            {DINO_AVATARS.map((dino) => {
              const isSelected = dino.key === avatarKey
              return (
                <button
                  key={dino.key}
                  type="button"
                  onClick={() => setAvatarKey(dino.key)}
                  aria-label={`Select ${dino.label}`}
                  className={cn(
                    'group relative flex flex-col items-center gap-1.5 rounded-2xl p-2 transition-all cursor-pointer',
                    isSelected
                      ? 'bg-primary/15 ring-2 ring-primary shadow-xs scale-105'
                      : 'bg-muted/40 hover:bg-muted/80',
                  )}
                >
                  <DinoAvatar avatarKey={dino.key} size="md" border={false} />
                  <span className="text-[11px] font-medium text-foreground truncate max-w-full">
                    {dino.name}
                  </span>
                  {isSelected && (
                    <span className="absolute -top-1 -right-1 flex size-4 items-center justify-center rounded-full bg-primary text-primary-foreground">
                      <Check className="size-2.5 stroke-[3]" />
                    </span>
                  )}
                </button>
              )
            })}
          </div>
        </div>

        {/* Nickname Input */}
        <div className="flex flex-col gap-1.5">
          <label
            htmlFor="dino-nickname"
            className="text-xs font-bold uppercase tracking-wider text-muted-foreground"
          >
            Party Nickname
          </label>
          <Input
            id="dino-nickname"
            value={nickname}
            onChange={(e) => {
              setNickname(e.target.value)
              if (error) setError(null)
            }}
            placeholder="e.g. RexPlayer, TriceraTop"
            maxLength={20}
            autoComplete="nickname"
          />
          {error && <p className="text-xs font-medium text-destructive">{error}</p>}
        </div>
      </div>

      <DialogFooter>
        <Button variant="secondary" onClick={onClose}>
          Cancel
        </Button>
        <Button onClick={handleSave}>Save Profile</Button>
      </DialogFooter>
    </>
  )
}

export function ProfileModal({ open, onOpenChange }: ProfileModalProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md">
        <ProfileForm onClose={() => onOpenChange(false)} />
      </DialogContent>
    </Dialog>
  )
}
