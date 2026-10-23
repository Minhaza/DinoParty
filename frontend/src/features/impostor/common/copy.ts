// Player-facing words of the impostor games. Written for DinoParty (originality checklist O4,
// decisions-and-risks §9.1): roles are "artist" and "impostor"; nothing is taken from the tabletop
// game the idea came from.

import type { ImpostorOutcome } from './types'

export const ROLE_COPY = {
  label: 'Your role',
  categoryLabel: 'Category',
  hiddenCategory: 'Revealed at the end',
  artist: {
    title: "You're an artist",
    secretLabel: 'Secret word',
    hint: 'One dino here is bluffing. Draw enough to prove you know the word — not so much that they can guess it.',
  },
  impostor: {
    title: "You're the impostor",
    secretLabel: 'Secret word',
    secret: 'Not for you this time',
    hint: 'You only know the category. Watch the others, blend in, and keep your claws steady.',
  },
  spectator: {
    title: "You're watching",
    secretLabel: 'Secret word',
    secret: 'Revealed at the end',
    hint: 'Sit back and spot the bluffer — the word and the impostor are shown when the round ends.',
  },
} as const

export const OUTCOME_COPY: Record<ImpostorOutcome, { headline: string; detail: string }> = {
  ESCAPED: {
    headline: 'The impostor slipped away!',
    detail: 'Nobody pointed enough claws at them. The impostor scores.',
  },
  GUESSED: {
    headline: 'Caught — but they cracked the word!',
    detail: 'The impostor was found and still named the secret word. The impostor scores.',
  },
  CAUGHT: {
    headline: 'Impostor caught!',
    detail: 'They could not name the word. Every artist scores, with a bonus for the sharp-eyed.',
  },
  IMPOSTOR_LEFT: {
    headline: 'The impostor left the game',
    detail: 'No points this round. The word is revealed below.',
  },
}

export const LEFT_PLAYER_NAME = 'A dino who left'

export function playerName(
  playersById: ReadonlyMap<string, { nickname: string }>,
  guestId: string | null | undefined,
): string {
  if (!guestId) return LEFT_PLAYER_NAME
  return playersById.get(guestId)?.nickname ?? LEFT_PLAYER_NAME
}
