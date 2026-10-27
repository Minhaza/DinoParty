import * as React from 'react'

import { DinoArtwork } from '@/components/art/DinoArtwork'
import { DEFAULT_AVATAR_KEY, DINO_AVATARS, type DinoAvatarKey } from '@/components/dinoAvatars'
import { cn } from '@/lib/utils'

const SIZE_CLASSES = {
  xs: 'size-6',
  sm: 'size-8',
  md: 'size-10',
  lg: 'size-14',
  xl: 'size-20',
  '2xl': 'size-28',
} as const

export interface DinoAvatarProps extends React.HTMLAttributes<HTMLDivElement> {
  avatarKey?: string | null
  size?: keyof typeof SIZE_CLASSES
  border?: boolean
  alt?: string
}

export function DinoAvatar({
  avatarKey = 'dino-rex',
  size = 'md',
  border = true,
  alt,
  className,
  ...props
}: DinoAvatarProps) {
  const normalizedKey = (
    avatarKey && DINO_AVATARS.some((d) => d.key === avatarKey) ? avatarKey : DEFAULT_AVATAR_KEY
  ) as DinoAvatarKey

  const meta = DINO_AVATARS.find((d) => d.key === normalizedKey) ?? DINO_AVATARS[0]
  const isDecorative = alt === ''
  const accessibleLabel = isDecorative ? undefined : (alt ?? `${meta.name} dinosaur avatar`)

  return (
    <div
      role={isDecorative ? undefined : 'img'}
      aria-hidden={isDecorative ? true : undefined}
      aria-label={accessibleLabel}
      className={cn(
        'relative inline-flex shrink-0 items-center justify-center overflow-hidden rounded-full transition-transform',
        SIZE_CLASSES[size],
        meta.bgClass,
        border && 'ring-2 ring-border/80 shadow-xs',
        className,
      )}
      {...props}
    >
      <DinoArtwork avatarKey={normalizedKey} className="size-full" />
    </div>
  )
}
