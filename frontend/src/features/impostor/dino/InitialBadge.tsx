import { cn } from '@/lib/utils'

export interface InitialBadgeProps {
  initial: string
  /** The player's session colour; `null` before it is known. */
  color: string | null | undefined
  className?: string
}

/**
 * A player's colour and initial together, so strokes and seats are never told apart by colour
 * alone (D56). The initial sits on the card background (always readable); the colour is the ring
 * and a dot, the same marks the canvas uses.
 */
export function InitialBadge({ initial, color, className }: InitialBadgeProps) {
  return (
    <span
      aria-hidden="true"
      className={cn(
        'relative inline-flex size-8 shrink-0 items-center justify-center rounded-full border-[3px] bg-card text-xs font-extrabold text-foreground',
        className,
      )}
      style={{ borderColor: color ?? 'var(--color-border)' }}
    >
      {initial}
      <span
        className="absolute -bottom-0.5 -right-0.5 size-2.5 rounded-full ring-2 ring-card"
        style={{ backgroundColor: color ?? 'transparent' }}
      />
    </span>
  )
}
