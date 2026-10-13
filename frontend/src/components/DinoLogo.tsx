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
      {/* Rex's head with beret: the brand mark */}
      <svg
        aria-hidden="true"
        viewBox="90 8 225 185"
        className={cn('shrink-0 drop-shadow-sm', ICON_SIZES[size])}
        fill="none"
      >
        <path
          d="M100 108 C98 52 152 28 202 42 C252 54 304 90 304 130 C304 170 266 182 228 180 C190 182 126 176 108 146 Z"
          fill="#2cb8a2"
          stroke="#10213d"
          strokeWidth="9"
          strokeLinejoin="round"
        />
        <path
          d="M214 146 Q262 184 300 140 Q262 160 214 146 Z"
          fill="#7a2e3b"
          stroke="#10213d"
          strokeWidth="7"
          strokeLinejoin="round"
        />
        <circle cx="214" cy="96" r="19" fill="#fff" stroke="#10213d" strokeWidth="7" />
        <circle cx="220" cy="98" r="10" fill="#10213d" />
        <path
          d="M112 70 C112 26 196 14 226 42 C206 56 150 70 112 70 Z"
          fill="#2f4f8f"
          stroke="#10213d"
          strokeWidth="9"
          strokeLinejoin="round"
        />
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
