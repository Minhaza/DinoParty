import type { PlayerDto } from '@/api/types'
import { DinoAvatar } from '@/components/DinoAvatar'
import { cn } from '@/lib/utils'

import { DEPARTED, resolveContributor } from './albumCopy'

export interface TelephoneContributorBadgeProps {
  guestId: string | null
  nickname: string | null
  players: readonly PlayerDto[]
  size?: 'xs' | 'sm'
  className?: string
}

export function TelephoneContributorBadge({
  guestId,
  nickname,
  players,
  size = 'sm',
  className,
}: TelephoneContributorBadgeProps) {
  const who = resolveContributor(guestId, nickname, players)
  return (
    <span className={cn('inline-flex min-w-0 items-center gap-2', className)}>
      <DinoAvatar avatarKey={who.avatarKey} size={size} border={false} />
      <span className="min-w-0 truncate font-bold text-foreground">{who.nickname}</span>
      {who.departed && who.nickname !== DEPARTED && (
        <span className="shrink-0 text-xs font-medium text-muted-foreground">(left)</span>
      )}
    </span>
  )
}
