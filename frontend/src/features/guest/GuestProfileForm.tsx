import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Check } from 'lucide-react'
import * as React from 'react'

import { describeError, fieldError } from '@/api/errors'
import { createGuest, guestQueryKey, updateMe } from '@/api/guests'
import type { GuestDto } from '@/api/types'
import { DinoAvatar } from '@/components/DinoAvatar'
import { DEFAULT_AVATAR_KEY, DINO_AVATARS } from '@/components/dinoAvatars'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'
import { useAuthStore } from '@/stores/authStore'

const NICKNAME_MIN = 2
const NICKNAME_MAX = 20

export interface GuestProfileFormProps {
  /** Existing profile to edit; omit to create a new guest. */
  guest?: GuestDto | null
  submitLabel?: string
  onDone?: (guest: GuestDto) => void
  onCancel?: () => void
}

/** Creates a guest (nickname + avatar, no account) or edits the current one. */
export function GuestProfileForm({
  guest = null,
  submitLabel,
  onDone,
  onCancel,
}: GuestProfileFormProps) {
  const queryClient = useQueryClient()
  const setToken = useAuthStore((s) => s.setToken)
  const [nickname, setNickname] = React.useState(guest?.nickname ?? '')
  const [avatarKey, setAvatarKey] = React.useState(guest?.avatarKey ?? DEFAULT_AVATAR_KEY)
  const [localError, setLocalError] = React.useState<string | null>(null)
  const [saved, setSaved] = React.useState(false)

  const mutation = useMutation({
    mutationFn: async (profile: { nickname: string; avatarKey: string }) => {
      if (guest) return updateMe(profile)
      const created = await createGuest(profile)
      setToken(created.token)
      return created.guest
    },
    onSuccess: (result) => {
      queryClient.setQueryData(guestQueryKey, result)
      setSaved(true)
      onDone?.(result)
    },
  })

  const errorText =
    localError ?? fieldError(mutation.error, 'nickname') ?? fieldError(mutation.error, 'avatarKey')
  const generalError = mutation.isError && !errorText ? describeError(mutation.error) : null

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    const trimmed = nickname.trim()
    if (trimmed.length < NICKNAME_MIN || trimmed.length > NICKNAME_MAX) {
      setLocalError(`Nickname must be ${NICKNAME_MIN}–${NICKNAME_MAX} characters.`)
      return
    }
    setLocalError(null)
    setSaved(false)
    mutation.mutate({ nickname: trimmed, avatarKey })
  }

  const label = DINO_AVATARS.find((d) => d.key === avatarKey)?.label

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-6" noValidate>
      <div className="flex flex-col items-center gap-2 rounded-2xl bg-secondary/50 p-4">
        <DinoAvatar avatarKey={avatarKey} size="xl" className="shadow-md" />
        <div className="text-center">
          <p className="text-lg font-bold text-foreground">{nickname.trim() || 'Your Nickname'}</p>
          <p className="text-xs text-muted-foreground">{label}</p>
        </div>
      </div>

      <fieldset className="flex flex-col gap-2">
        <legend className="text-xs font-bold uppercase tracking-wider text-muted-foreground">
          Select dinosaur
        </legend>
        <div className="grid grid-cols-3 gap-2.5 sm:grid-cols-6">
          {DINO_AVATARS.map((dino) => {
            const selected = dino.key === avatarKey
            return (
              <button
                key={dino.key}
                type="button"
                onClick={() => setAvatarKey(dino.key)}
                aria-label={`Select ${dino.label}`}
                aria-pressed={selected}
                className={cn(
                  'relative flex cursor-pointer flex-col items-center gap-1.5 rounded-2xl p-2 outline-none transition-all focus-visible:ring-[3px] focus-visible:ring-ring/50',
                  selected
                    ? 'scale-105 bg-primary/15 shadow-xs ring-2 ring-primary'
                    : 'bg-muted/40 hover:bg-muted/80',
                )}
              >
                <DinoAvatar avatarKey={dino.key} size="md" border={false} alt="" />
                <span className="max-w-full truncate text-[11px] font-medium text-foreground">
                  {dino.name}
                </span>
                {selected && (
                  <span
                    aria-hidden
                    className="absolute -right-1 -top-1 flex size-4 items-center justify-center rounded-full bg-primary text-primary-foreground"
                  >
                    <Check className="size-2.5 stroke-[3]" />
                  </span>
                )}
              </button>
            )
          })}
        </div>
      </fieldset>

      <div className="flex flex-col gap-1.5">
        <label
          htmlFor="guest-nickname"
          className="text-xs font-bold uppercase tracking-wider text-muted-foreground"
        >
          Party nickname
        </label>
        <Input
          id="guest-nickname"
          value={nickname}
          onChange={(e) => {
            setNickname(e.target.value)
            setLocalError(null)
            setSaved(false)
            mutation.reset()
          }}
          placeholder="e.g. RexPlayer, TriceraTop"
          maxLength={NICKNAME_MAX}
          autoComplete="nickname"
          aria-invalid={errorText !== null}
          aria-describedby={errorText ? 'guest-nickname-error' : undefined}
        />
        {errorText && (
          <p
            id="guest-nickname-error"
            role="alert"
            className="text-xs font-medium text-destructive"
          >
            {errorText}
          </p>
        )}
      </div>

      {generalError && (
        <p role="alert" className="text-sm font-medium text-destructive">
          {generalError}
        </p>
      )}
      <p aria-live="polite" className="min-h-4 text-sm font-medium text-emerald-600">
        {saved && guest ? 'Profile saved.' : ''}
      </p>

      <div className="flex justify-end gap-2">
        {onCancel && (
          <Button type="button" variant="secondary" onClick={onCancel}>
            Cancel
          </Button>
        )}
        <Button type="submit" disabled={mutation.isPending}>
          {mutation.isPending
            ? 'Saving…'
            : (submitLabel ?? (guest ? 'Save profile' : 'Create profile'))}
        </Button>
      </div>
    </form>
  )
}
