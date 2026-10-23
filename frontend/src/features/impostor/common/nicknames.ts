/**
 * Merges the active room roster with historical nicknames (api-contracts §3.11, G2).
 * Active players win; `nicknames` fills the gaps for players who disconnected or left.
 */
export function rosterNames(
  players: readonly { guestId: string; nickname: string }[],
  nicknames?: Record<string, string>,
): Map<string, { guestId: string; nickname: string }> {
  const map = new Map<string, { guestId: string; nickname: string }>()
  if (nicknames) {
    for (const [guestId, nickname] of Object.entries(nicknames)) {
      if (nickname) {
        map.set(guestId, { guestId, nickname })
      }
    }
  }
  for (const p of players) {
    if (p.guestId && p.nickname) {
      map.set(p.guestId, { guestId: p.guestId, nickname: p.nickname })
    }
  }
  return map
}
