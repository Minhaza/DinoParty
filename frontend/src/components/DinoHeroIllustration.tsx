import { DinoAvatar } from '@/components/DinoAvatar'

export function DinoHeroIllustration() {
  return (
    <div className="relative mx-auto w-full max-w-5xl overflow-hidden rounded-3xl border border-amber-200/60 bg-gradient-to-b from-sky-200/60 via-amber-100/40 to-emerald-100/50 p-6 shadow-sm dark:border-stone-800 dark:from-sky-950/40 dark:via-stone-900/60 dark:to-emerald-950/40 sm:p-10">
      {/* Sunny Prehistoric Sky, Volcano, Palm Trees & Clouds */}
      <svg
        className="pointer-events-none absolute inset-0 size-full opacity-60 dark:opacity-30"
        viewBox="0 0 1000 400"
        fill="none"
        preserveAspectRatio="none"
        xmlns="http://www.w3.org/2000/svg"
        aria-hidden="true"
      >
        {/* Soft Golden Sunlight Rays */}
        <path d="M500 0 L350 400 L450 400 Z" fill="#FEF08A" opacity="0.35" />
        <path d="M500 0 L550 400 L680 400 Z" fill="#FEF08A" opacity="0.3" />

        {/* Fluffy Clouds */}
        <ellipse cx="200" cy="70" rx="65" ry="24" fill="#FFFFFF" opacity="0.9" />
        <ellipse cx="240" cy="62" rx="45" ry="22" fill="#FFFFFF" opacity="0.9" />
        <ellipse cx="780" cy="80" rx="75" ry="26" fill="#FFFFFF" opacity="0.85" />
        <ellipse cx="830" cy="72" rx="50" ry="22" fill="#FFFFFF" opacity="0.85" />

        {/* Distant Warm Prehistoric Mountains */}
        <polygon points="180,400 320,180 440,400" fill="#FDBA74" opacity="0.5" />
        <polygon points="300,210 320,180 345,210" fill="#EF4444" opacity="0.75" />
        {/* Volcano smoke puffs */}
        <circle cx="330" cy="155" r="16" fill="#FFFFFF" opacity="0.7" />
        <circle cx="345" cy="125" r="22" fill="#FFFFFF" opacity="0.55" />
        <circle cx="365" cy="92" r="28" fill="#FFFFFF" opacity="0.4" />

        {/* Lush Green Hills */}
        <path
          d="M0 400 Q200 280 450 320 T900 270 L1000 300 L1000 400 Z"
          fill="#86EFAC"
          opacity="0.45"
        />
        <path d="M0 400 Q250 310 550 340 T1000 310 L1000 400 Z" fill="#4ADE80" opacity="0.35" />
      </svg>

      {/* Characters Scene */}
      <div className="relative flex flex-col items-center justify-between gap-8 md:flex-row">
        {/* Rex The Artist (Left) */}
        <div className="flex items-end gap-3.5">
          <div className="relative">
            <DinoAvatar
              avatarKey="dino-rex"
              size="2xl"
              className="shadow-xl ring-4 ring-white dark:ring-stone-800"
            />
            {/* Paintbrush badge */}
            <div className="absolute -bottom-2 -right-2 flex size-9 items-center justify-center rounded-full bg-amber-400 text-stone-900 shadow-md ring-2 ring-white text-lg">
              🖌️
            </div>
          </div>

          {/* Mini Easel */}
          <div className="hidden rounded-2xl border-2 border-amber-800/30 bg-white p-3 shadow-md dark:bg-stone-900 sm:block">
            <div className="flex h-20 w-24 flex-col items-center justify-center rounded-xl bg-amber-50/90 p-2 dark:bg-stone-800">
              <span className="text-3xl animate-bounce">🌋</span>
              <span className="text-[10px] font-extrabold text-stone-600 dark:text-stone-300">
                "Volcano!"
              </span>
            </div>
          </div>
        </div>

        {/* Flying Ptera in the sky (Center-Right top) */}
        <div className="flex flex-col items-center gap-2">
          <div className="flex items-center gap-2 rounded-full border border-amber-300/80 bg-white/90 px-4 py-1.5 shadow-sm backdrop-blur-xs dark:bg-stone-900/90">
            <span className="text-xs font-bold text-amber-800 dark:text-amber-300">
              🦖 Playful Party Games
            </span>
          </div>

          <div className="flex items-center gap-3">
            <div className="relative">
              <DinoAvatar
                avatarKey="dino-ptera"
                size="lg"
                className="shadow-md ring-2 ring-white dark:ring-stone-800 animate-pulse"
              />
              <span className="absolute -top-1 -right-1 text-xs">✨</span>
            </div>
            <DinoAvatar
              avatarKey="dino-stego"
              size="md"
              className="shadow-sm ring-2 ring-white dark:ring-stone-800"
            />
            <DinoAvatar
              avatarKey="dino-mite"
              size="md"
              className="shadow-sm ring-2 ring-white dark:ring-stone-800"
            />
          </div>
        </div>

        {/* Triceratops & Wooden Sign (Right) */}
        <div className="flex items-end gap-3">
          {/* Wooden Sign Post */}
          <div className="hidden flex-col items-center sm:flex">
            <div className="rounded-xl border-2 border-amber-900/40 bg-amber-100 px-3 py-1.5 shadow-sm dark:bg-amber-950/80">
              <p className="text-[11px] font-black uppercase tracking-wider text-amber-900 dark:text-amber-200">
                GOOD GAMES
              </p>
              <p className="text-[11px] font-black uppercase tracking-wider text-amber-800 dark:text-amber-300">
                GOOD FRIENDS
              </p>
            </div>
            <div className="h-6 w-2 bg-amber-800/60 rounded-b-xs" />
          </div>

          {/* Peeking Cute Triceratops */}
          <div className="relative">
            <DinoAvatar
              avatarKey="dino-tricera"
              size="2xl"
              className="shadow-xl ring-4 ring-white dark:ring-stone-800"
            />
            <div className="absolute -bottom-1 -left-1 flex size-8 items-center justify-center rounded-full bg-orange-400 text-white shadow-md ring-2 ring-white text-base">
              🎉
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
