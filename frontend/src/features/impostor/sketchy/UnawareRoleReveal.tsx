import { Siren } from 'lucide-react'

import type { ImpostorCategory } from '../common/types'

export interface UnawareRoleRevealProps {
  category: ImpostorCategory | null
}

/**
 * The first moment an unaware impostor learns their role (`[SS-3]`): shown only with the private
 * `IMPOSTOR_GUESS_OPEN`, so nothing before it can differ from an artist's screen. The fade-in is
 * reduced-motion safe.
 */
export function UnawareRoleReveal({ category }: UnawareRoleRevealProps) {
  return (
    <section
      aria-label="Your secret role"
      data-testid="unaware-role-reveal"
      className="flex flex-col items-center gap-2 rounded-2xl border border-destructive/40 bg-destructive/5 p-5 text-center animate-in fade-in zoom-in-95 duration-300 motion-reduce:animate-none"
    >
      <Siren aria-hidden="true" className="size-8 text-destructive" />
      <h2 className="font-fredoka text-xl font-bold text-destructive">You were the impostor!</h2>
      <p className="text-sm text-muted-foreground">
        Your word was a decoy, so nobody — not even you — knew.
        {category ? ` The real word is somewhere in ${category.label}.` : ''} Guess it now to still
        win the round.
      </p>
    </section>
  )
}
