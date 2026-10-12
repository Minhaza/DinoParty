import { cn } from '@/lib/utils'

export interface DinoLogoProps {
  size?: 'sm' | 'md' | 'lg'
  showText?: boolean
  className?: string
}

export function DinoLogo({ size = 'md', showText = true, className }: DinoLogoProps) {
  const iconSizes = {
    sm: 'size-7',
    md: 'size-9',
    lg: 'size-12',
  }

  const textSizes = {
    sm: 'text-lg',
    md: 'text-xl',
    lg: 'text-3xl',
  }

  return (
    <div
      className={cn(
        'inline-flex items-center gap-2.5 font-black tracking-tight select-none',
        className,
      )}
    >
      {/* Playful Cyan/Teal Dino Mascot Icon */}
      <div
        className={cn(
          'relative flex shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br from-cyan-400 via-teal-400 to-emerald-500 p-1 text-white shadow-xs transition-transform hover:scale-105',
          iconSizes[size],
        )}
      >
        <svg
          viewBox="0 0 100 100"
          className="size-full"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
        >
          {/* Head & Snout */}
          <path
            d="M26 26 C26 14, 52 14, 68 18 C80 22, 90 32, 90 46 C90 60, 78 66, 64 66 L48 66 C36 66, 26 76, 26 90 L20 90 C18 70, 20 42, 26 26 Z"
            fill="#FFFFFF"
          />
          {/* Yellow Spikes */}
          <polygon points="22,22 14,28 24,32" fill="#FDE047" />
          <polygon points="18,40 10,46 20,50" fill="#FDE047" />
          <polygon points="16,58 8,64 18,68" fill="#FDE047" />
          {/* Cute Eye */}
          <circle cx="62" cy="34" r="8" fill="#0F172A" />
          <circle cx="65" cy="32" r="3" fill="#FFFFFF" />
          {/* Smile & Cheek */}
          <path d="M68 56 Q76 56 80 50" stroke="#0F172A" strokeWidth="3" strokeLinecap="round" />
          <ellipse cx="56" cy="46" rx="5" ry="3.5" fill="#FB7185" opacity="0.8" />
        </svg>
      </div>

      {/* Two-Tone Brand Wordmark: "Dino" in slate, "Party" in vibrant bright orange */}
      {showText && (
        <span className={cn('font-black tracking-tight', textSizes[size])}>
          <span className="text-foreground">Dino</span>
          <span className="text-orange-500 dark:text-orange-400">Party</span>
        </span>
      )}
    </div>
  )
}
