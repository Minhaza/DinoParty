import sheet from '@/assets/characters/dinosaurs.png'
import { cn } from '@/lib/utils'

export interface DinoLogoProps {
  size?: 'sm' | 'md' | 'lg'
  showText?: boolean
  className?: string
}

const ICON_SIZES = { sm: 'size-8', md: 'size-10', lg: 'size-14' } as const
const TEXT_SIZES = { sm: 'text-xl', md: 'text-2xl', lg: 'text-4xl' } as const

export function DinoLogo({ size = 'md', showText = true, className }: DinoLogoProps) {
  return (
    <div
      className={cn('inline-flex items-center gap-2 font-display font-bold select-none', className)}
    >
      {/* Crop the current Rex atlas to keep his face and orange beret legible at header size. */}
      <svg
        aria-hidden="true"
        focusable="false"
        viewBox="65 0 300 270"
        className={cn('shrink-0 drop-shadow-sm', ICON_SIZES[size])}
      >
        <image href={sheet} width="1536" height="1024" />
      </svg>
      {showText && (
        <span className={cn('leading-none tracking-tight', TEXT_SIZES[size])}>
          <span className="text-foreground">Dino</span>
          <span className="text-accent">Party</span>
        </span>
      )}
    </div>
  )
}
