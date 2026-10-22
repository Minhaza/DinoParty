// Initial badges that tell players apart without relying on colour ([DI-6], D56).

export const LEFT_PLAYER_INITIAL = '?'

function letters(nickname: string): string[] {
  return Array.from(nickname.trim().replace(/\s+/g, '')).filter((c) => /[\p{L}\p{N}]/u.test(c))
}

/**
 * One badge per guest, in the given order: the first letter, two letters when that clashes with
 * someone else's, and the seat number appended when even that is not unique.
 */
export function initialsFor(
  players: ReadonlyArray<{ guestId: string; nickname: string | null }>,
): Map<string, string> {
  const first = players.map((p) => {
    const chars = p.nickname ? letters(p.nickname) : []
    return {
      id: p.guestId,
      one: (chars[0] ?? LEFT_PLAYER_INITIAL).toUpperCase(),
      two: ((chars[0] ?? '') + (chars[1] ?? '')).toUpperCase() || LEFT_PLAYER_INITIAL,
    }
  })
  const count = (key: 'one' | 'two', value: string) => first.filter((f) => f[key] === value).length

  const result = new Map<string, string>()
  first.forEach((f, i) => {
    let badge = count('one', f.one) === 1 ? f.one : f.two
    if (badge !== f.one && count('two', f.two) > 1) badge = `${f.one}${i + 1}`
    result.set(f.id, badge)
  })
  return result
}
