const INK = '#10213d'

/** Compact palette icon (interface icon, not artwork) for the Draw & Guess card header. */
export function PaletteBadge({ className }: { className?: string }) {
  return (
    <svg aria-hidden="true" viewBox="0 0 64 64" className={className} fill="none">
      <path
        d="M32 6 C16 6 5 18 5 32 c0 15 11 26 25 26 6 0 8-4 6-8 -2-4 0-8 5-8 h8 c6 0 10-4 10-10 C59 17 47 6 32 6 Z"
        fill="#ffd9a0"
        stroke={INK}
        strokeWidth="3"
        strokeLinejoin="round"
      />
      <circle cx="19" cy="28" r="5" fill="#f36b36" />
      <circle cx="30" cy="17" r="5" fill="#11956b" />
      <circle cx="44" cy="19" r="5" fill="#7565e8" />
      <circle cx="21" cy="43" r="5" fill="#3aa6d8" />
    </svg>
  )
}

/** Compact pencil icon (interface icon, not artwork) for the Dino Telephone card header. */
export function PencilBadge({ className }: { className?: string }) {
  return (
    <svg aria-hidden="true" viewBox="0 0 64 64" className={className} fill="none">
      <g transform="rotate(40 32 32)">
        <rect
          x="24"
          y="4"
          width="16"
          height="42"
          rx="3"
          fill="#7565e8"
          stroke={INK}
          strokeWidth="3"
        />
        <rect
          x="24"
          y="4"
          width="16"
          height="9"
          rx="3"
          fill="#f36b36"
          stroke={INK}
          strokeWidth="3"
        />
        <path
          d="M24 46 L32 62 L40 46 Z"
          fill="#ffe0b0"
          stroke={INK}
          strokeWidth="3"
          strokeLinejoin="round"
        />
      </g>
    </svg>
  )
}

/** Neutral dino-egg icon for a game this client has no artwork for yet. */
export function EggBadge({ className }: { className?: string }) {
  return (
    <svg aria-hidden="true" viewBox="0 0 64 64" className={className} fill="none">
      <path
        d="M32 5 C19 5 10 25 10 38 c0 13 10 21 22 21 s22-8 22-21 C54 25 45 5 32 5 Z"
        fill="#fff3dc"
        stroke={INK}
        strokeWidth="3"
        strokeLinejoin="round"
      />
      <circle cx="24" cy="30" r="4.5" fill="#11956b" />
      <circle cx="39" cy="24" r="3.5" fill="#7565e8" />
      <circle cx="40" cy="42" r="5" fill="#f36b36" />
      <circle cx="25" cy="47" r="3" fill="#3aa6d8" />
    </svg>
  )
}

/** Small confetti burst for celebration moments. Pure CSS motion, hidden by reduced-motion. */
export function Confetti({ count = 24 }: { count?: number }) {
  const colors = ['#f36b36', '#11956b', '#7565e8', '#f5b335', '#3aa6d8']
  return (
    <div aria-hidden="true" className="pointer-events-none absolute inset-0 overflow-hidden">
      {Array.from({ length: count }, (_, i) => (
        <span
          key={i}
          className="absolute top-0 block h-3 w-2 animate-confetti rounded-[2px] motion-reduce:hidden"
          style={{
            left: `${(i * 37) % 100}%`,
            background: colors[i % colors.length],
            animationDelay: `${(i % 8) * 0.35}s`,
            animationDuration: `${3 + (i % 5) * 0.4}s`,
          }}
        />
      ))}
    </div>
  )
}
