import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Check } from 'lucide-react'
import * as React from 'react'

import { describeError, fieldError } from '@/api/errors'
import { createGuest, guestQueryKey, updateMe } from '@/api/guests'
import type { GuestDto } from '@/api/types'
import { DinoAvatar } from '@/components/DinoAvatar'
import {
  DEFAULT_AVATAR_KEY,
  DINO_AVATARS,
  DINO_BLURBS,
  type DinoAvatarKey,
} from '@/components/dinoAvatars'
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
      {/* Selected character: large portrait, name and personality */}
      <div
        aria-live="polite"
        className="flex items-center gap-5 rounded-[22px] bg-[linear-gradient(135deg,var(--secondary),var(--peach))] p-5 shadow-inner"
      >
        <div key={avatarKey} className="shrink-0 animate-pop">
          <DinoAvatar
            avatarKey={avatarKey}
            alt={`${label ?? 'Dino'} portrait`}
            border={false}
            className="size-28 rounded-[28px] shadow-card sm:size-36"
          />
        </div>
        <div className="min-w-0">
          <p className="font-display text-2xl font-semibold leading-tight text-foreground">
            {DINO_AVATARS.find((d) => d.key === avatarKey)?.name}
          </p>
          <p className="truncate text-sm font-bold text-primary">
            {nickname.trim() || 'Your nickname'}
          </p>
          <p className="mt-1 text-sm font-semibold leading-snug text-muted-foreground">
            {DINO_BLURBS[avatarKey as DinoAvatarKey] ?? ''}
          </p>
        </div>
      </div>

      <fieldset className="flex flex-col gap-3">
        <legend className="text-xs font-extrabold uppercase tracking-wider text-muted-foreground">
          Select dinosaur
        </legend>
        <div className="grid grid-cols-3 gap-3 sm:grid-cols-6">
          {DINO_AVATARS.map((dino) => {
            const selected = dino.key === avatarKey
            return (
              <button
                key={dino.key}
                type="button"
                onClick={() => setAvatarKey(dino.key)}
                aria-label={`Select ${dino.label}`}
                aria-pressed={selected}
                className="group flex cursor-pointer flex-col items-center gap-1.5 rounded-2xl p-1 outline-none focus-visible:ring-[3px] focus-visible:ring-ring/60"
              >
                <span
                  className={cn(
                    'relative rounded-full p-0.5 transition-all group-hover:scale-110',
                    selected ? 'bg-primary shadow-tactile' : 'bg-transparent',
                  )}
                >
                  <DinoAvatar avatarKey={dino.key} size="lg" border={false} alt="" />
                  {selected && (
                    <span
                      aria-hidden
                      className="absolute -bottom-0.5 -right-0.5 flex size-5 items-center justify-center rounded-full bg-primary text-primary-foreground ring-2 ring-card"
                    >
                      <Check className="size-3 stroke-[3]" />
                    </span>
                  )}
                </span>
                <span
                  className={cn(
                    'max-w-full truncate text-xs font-bold',
                    selected ? 'text-primary' : 'text-muted-foreground',
                  )}
                >
                  {dino.name}
                </span>
              </button>
            )
          })}
        </div>
      </fieldset>

      <div className="flex flex-col gap-1.5">
        <label
          htmlFor="guest-nickname"
          className="text-xs font-extrabold uppercase tracking-wider text-muted-foreground"
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
      <p aria-live="polite" className="min-h-4 text-sm font-bold text-primary">
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
