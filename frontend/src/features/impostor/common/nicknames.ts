/**
 * Merges the active room roster with historical nicknames (api-contracts §3.11, G2).
 * Active players win (kept as-is, so fields like `avatarKey` survive); `nicknames` fills the gaps
 * for players who disconnected or left.
 */
export function rosterNames<P extends { guestId: string; nickname: string }>(
  players: readonly P[],
  nicknames?: Record<string, string>,
): Map<string, P | { guestId: string; nickname: string }> {
  const map = new Map<string, P | { guestId: string; nickname: string }>()
  if (nicknames) {
    for (const [guestId, nickname] of Object.entries(nicknames)) {
      if (nickname) {
        map.set(guestId, { guestId, nickname })
      }
    }
  }
  for (const p of players) {
    if (p.guestId && p.nickname) {
      map.set(p.guestId, p)
    }
  }
  return map
}
