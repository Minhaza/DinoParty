import * as React from 'react'

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
  const accessibleLabel = alt ?? `${meta.name} dinosaur avatar`

  return (
    <div
      role="img"
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
      <DinoSvg avatarKey={normalizedKey} />
    </div>
  )
}

function DinoSvg({ avatarKey }: { avatarKey: DinoAvatarKey }) {
  switch (avatarKey) {
    case 'dino-rex':
      return (
        <svg
          viewBox="0 0 100 100"
          className="size-full p-1 drop-shadow-xs"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
        >
          {/* Head & Snout */}
          <path
            d="M30 22 C30 14, 52 14, 66 18 C78 22, 88 32, 88 44 C88 56, 76 62, 64 62 L48 62 C38 62, 30 70, 30 82 L24 82 C22 66, 24 38, 30 22 Z"
            fill="#22C55E"
          />
          {/* Jaw */}
          <path
            d="M52 64 C64 64, 76 60, 84 52 C84 62, 74 72, 58 72 L42 72 C46 66, 48 64, 52 64 Z"
            fill="#16A34A"
          />
          {/* Belly highlight */}
          <path
            d="M32 50 C38 42, 50 40, 56 46 C56 56, 48 76, 34 80 Z"
            fill="#86EFAC"
            opacity="0.6"
          />
          {/* Spikes */}
          <polygon points="26,18 20,24 28,28" fill="#F59E0B" />
          <polygon points="22,34 14,38 23,44" fill="#F59E0B" />
          <polygon points="20,50 12,54 21,58" fill="#F59E0B" />
          {/* Eye */}
          <circle cx="60" cy="32" r="8" fill="#FFFFFF" />
          <circle cx="63" cy="32" r="4.5" fill="#14532D" />
          <circle cx="65" cy="30" r="1.8" fill="#FFFFFF" />
          {/* Cute tooth */}
          <polygon points="70,62 74,68 78,62" fill="#FFFFFF" />
          {/* Nostril & blush */}
          <ellipse cx="80" cy="40" rx="2" ry="3" fill="#14532D" opacity="0.7" />
          <ellipse cx="56" cy="42" rx="4" ry="2.5" fill="#F43F5E" opacity="0.35" />
        </svg>
      )

    case 'dino-tricera':
      return (
        <svg
          viewBox="0 0 100 100"
          className="size-full p-1 drop-shadow-xs"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
        >
          {/* Frill / Crest */}
          <path
            d="M20 52 C15 30, 32 15, 50 15 C68 15, 85 30, 80 52 C88 38, 76 18, 50 12 C24 18, 12 38, 20 52 Z"
            fill="#EA580C"
          />
          <circle cx="28" cy="28" r="4" fill="#FDBA74" />
          <circle cx="50" cy="20" r="4.5" fill="#FDBA74" />
          <circle cx="72" cy="28" r="4" fill="#FDBA74" />
          {/* Head */}
          <ellipse cx="50" cy="56" rx="28" ry="24" fill="#F97316" />
          {/* Horns */}
          <path d="M34 38 L30 18 Q36 26 38 34 Z" fill="#FEF08A" stroke="#CA8A04" strokeWidth="1" />
          <path d="M66 38 L70 18 Q64 26 62 34 Z" fill="#FEF08A" stroke="#CA8A04" strokeWidth="1" />
          <path d="M48 64 L50 50 Q53 58 52 64 Z" fill="#FEF08A" stroke="#CA8A04" strokeWidth="1" />
          {/* Eyes */}
          <circle cx="38" cy="50" r="6" fill="#FFFFFF" />
          <circle cx="39" cy="50" r="3.2" fill="#7C2D12" />
          <circle cx="41" cy="48" r="1.2" fill="#FFFFFF" />
          <circle cx="62" cy="50" r="6" fill="#FFFFFF" />
          <circle cx="61" cy="50" r="3.2" fill="#7C2D12" />
          <circle cx="63" cy="48" r="1.2" fill="#FFFFFF" />
          {/* Smile */}
          <path d="M44 68 Q50 74 56 68" stroke="#7C2D12" strokeWidth="2.5" strokeLinecap="round" />
          {/* Cheeks */}
          <ellipse cx="32" cy="58" rx="4" ry="2.5" fill="#EF4444" opacity="0.35" />
          <ellipse cx="68" cy="58" rx="4" ry="2.5" fill="#EF4444" opacity="0.35" />
        </svg>
      )

    case 'dino-ptera':
      return (
        <svg
          viewBox="0 0 100 100"
          className="size-full p-1 drop-shadow-xs"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
        >
          {/* Wings background */}
          <path
            d="M12 60 C14 36, 32 30, 44 42 C40 56, 26 66, 12 60 Z"
            fill="#9333EA"
            opacity="0.8"
          />
          <path
            d="M88 60 C86 36, 68 30, 56 42 C60 56, 74 66, 88 60 Z"
            fill="#9333EA"
            opacity="0.8"
          />
          {/* Head crest */}
          <path d="M46 36 C34 18, 18 16, 12 18 C24 28, 38 34, 46 36 Z" fill="#C084FC" />
          {/* Head & Body */}
          <ellipse cx="50" cy="48" rx="18" ry="20" fill="#A855F7" />
          {/* Beak */}
          <path
            d="M44 50 L56 50 L68 76 C58 78, 52 74, 44 50 Z"
            fill="#FACC15"
            stroke="#CA8A04"
            strokeWidth="1.2"
          />
          {/* Eyes */}
          <circle cx="44" cy="42" r="5" fill="#FFFFFF" />
          <circle cx="45" cy="42" r="2.8" fill="#581C87" />
          <circle cx="46" cy="40" r="1.1" fill="#FFFFFF" />
          <circle cx="56" cy="42" r="5" fill="#FFFFFF" />
          <circle cx="55" cy="42" r="2.8" fill="#581C87" />
          <circle cx="56" cy="40" r="1.1" fill="#FFFFFF" />
          {/* Belly */}
          <ellipse cx="50" cy="58" rx="8" ry="10" fill="#E9D5FF" opacity="0.6" />
        </svg>
      )

    case 'dino-stego':
      return (
        <svg
          viewBox="0 0 100 100"
          className="size-full p-1 drop-shadow-xs"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
        >
          {/* Plates */}
          <polygon points="24,34 32,16 40,32" fill="#F59E0B" stroke="#D97706" strokeWidth="1" />
          <polygon points="42,28 52,10 60,26" fill="#EC4899" stroke="#DB2777" strokeWidth="1" />
          <polygon points="62,32 72,18 78,36" fill="#8B5CF6" stroke="#7C3AED" strokeWidth="1" />
          {/* Body */}
          <path
            d="M20 70 C16 48, 30 32, 60 32 C78 32, 86 46, 84 62 C78 74, 60 78, 34 78 C24 78, 18 76, 20 70 Z"
            fill="#06B6D4"
          />
          {/* Snout */}
          <path d="M72 46 C86 48, 92 56, 86 64 C78 66, 72 60, 72 46 Z" fill="#0891B2" />
          {/* Eye */}
          <circle cx="72" cy="48" r="5" fill="#FFFFFF" />
          <circle cx="73" cy="48" r="2.8" fill="#164E63" />
          <circle cx="75" cy="46" r="1.1" fill="#FFFFFF" />
          {/* Back spots */}
          <circle cx="40" cy="50" r="3.5" fill="#67E8F9" opacity="0.8" />
          <circle cx="52" cy="46" r="4.5" fill="#67E8F9" opacity="0.8" />
          <circle cx="60" cy="54" r="3" fill="#67E8F9" opacity="0.8" />
          {/* Smile */}
          <path d="M82 58 Q86 62 82 64" stroke="#164E63" strokeWidth="2" strokeLinecap="round" />
        </svg>
      )

    case 'dino-mite':
      return (
        <svg
          viewBox="0 0 100 100"
          className="size-full p-1 drop-shadow-xs"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
        >
          {/* Egg shell cap or little head crest */}
          <path d="M38 18 Q50 12 62 18 Q66 26 62 30 Q50 26 38 30 Z" fill="#BEF264" />
          {/* Round head */}
          <circle cx="50" cy="50" r="32" fill="#84CC16" />
          {/* Belly */}
          <ellipse cx="50" cy="62" rx="18" ry="16" fill="#D9F99D" opacity="0.75" />
          {/* Big anime curious eyes */}
          <circle cx="36" cy="44" r="9" fill="#1E3A8A" />
          <circle cx="34" cy="41" r="3.5" fill="#FFFFFF" />
          <circle cx="39" cy="47" r="1.6" fill="#FFFFFF" />
          <circle cx="64" cy="44" r="9" fill="#1E3A8A" />
          <circle cx="62" cy="41" r="3.5" fill="#FFFFFF" />
          <circle cx="67" cy="47" r="1.6" fill="#FFFFFF" />
          {/* Cute open mouth / smile */}
          <path d="M45 56 Q50 63 55 56 Z" fill="#BE123C" />
          {/* Rosy Cheeks */}
          <ellipse cx="28" cy="52" rx="5" ry="3" fill="#F43F5E" opacity="0.4" />
          <ellipse cx="72" cy="52" rx="5" ry="3" fill="#F43F5E" opacity="0.4" />
        </svg>
      )

    case 'dino-lizard':
      return (
        <svg
          viewBox="0 0 100 100"
          className="size-full p-1 drop-shadow-xs"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
        >
          {/* Spikes / Magma horns */}
          <polygon points="32,28 36,12 44,26" fill="#F97316" stroke="#C2410C" strokeWidth="1" />
          <polygon points="50,22 56,8 64,22" fill="#FBBF24" stroke="#D97706" strokeWidth="1" />
          <polygon points="68,26 76,14 78,30" fill="#F97316" stroke="#C2410C" strokeWidth="1" />
          {/* Head */}
          <path
            d="M24 60 C20 40, 36 28, 54 28 C74 28, 86 42, 84 62 C82 76, 64 80, 48 80 C32 80, 24 74, 24 60 Z"
            fill="#EF4444"
          />
          {/* Magma cracks / spots */}
          <circle cx="42" cy="44" r="4" fill="#FDE047" opacity="0.85" />
          <circle cx="56" cy="38" r="3.5" fill="#FDE047" opacity="0.85" />
          <circle cx="48" cy="68" r="5" fill="#F97316" opacity="0.9" />
          {/* Fierce but cute eye */}
          <ellipse cx="64" cy="48" rx="7" ry="8" fill="#FEF08A" />
          <ellipse cx="66" cy="48" rx="3.5" ry="5.5" fill="#7F1D1D" />
          <circle cx="68" cy="45" r="1.5" fill="#FFFFFF" />
          {/* Snout tooth */}
          <path d="M72 64 Q78 68 84 64" stroke="#7F1D1D" strokeWidth="2.5" strokeLinecap="round" />
          <polygon points="76,64 78,70 81,64" fill="#FFFFFF" />
        </svg>
      )
  }
}
