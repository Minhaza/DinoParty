import type { RevealState } from './revealStore'
import type { AlbumEntryView, RevealCursor } from './types'

// Pure reads over the reveal store. Positions are compared in reveal order: album by album, entry
// by entry (api-contracts §3.10), so "furthest revealed" is a single number.

type RevealView = Pick<
  RevealState,
  'chains' | 'entriesPerChain' | 'cursor' | 'highWater' | 'browse' | 'entries'
>

export const entryKey = (chain: number, entry: number) => `${chain}:${entry}`

export function toLinear(position: RevealCursor, entriesPerChain: number): number {
  return position.chainIndex * entriesPerChain + position.entryIndex
}

export function fromLinear(linear: number, entriesPerChain: number): RevealCursor {
  return {
    chainIndex: Math.floor(linear / entriesPerChain),
    entryIndex: linear % entriesPerChain,
  }
}

/** The position on screen: the locally browsed one, else the shared cursor. */
export function shownPosition(s: RevealView): RevealCursor | null {
  return s.browse ?? s.cursor
}

/** Entries of the shown album up to the shown entry, in step order; only what the client holds. */
export function visibleEntries(s: RevealView): AlbumEntryView[] {
  const shown = shownPosition(s)
  if (!shown) return []
  const out: AlbumEntryView[] = []
  for (let e = 0; e <= shown.entryIndex; e++) {
    const entry = s.entries[entryKey(shown.chainIndex, e)]
    if (entry) out.push(entry)
  }
  return out
}

/** Every revealed entry of an album, in step order (the timeline of a completed album). */
export function albumEntries(s: RevealView, chain: number): AlbumEntryView[] {
  const out: AlbumEntryView[] = []
  for (let e = 0; e < s.entriesPerChain; e++) {
    const entry = s.entries[entryKey(chain, e)]
    if (entry) out.push(entry)
  }
  return out
}

export function isAlbumComplete(s: RevealView, chain: number): boolean {
  if (!s.highWater || s.entriesPerChain === 0) return false
  return toLinear(s.highWater, s.entriesPerChain) >= (chain + 1) * s.entriesPerChain - 1
}

/** "Album 2 of 5 — Step 3 of 6" for the shown position. */
export function positionLabel(s: RevealView): string {
  const shown = shownPosition(s)
  if (!shown) return ''
  return `Album ${shown.chainIndex + 1} of ${s.chains} — Step ${shown.entryIndex + 1} of ${s.entriesPerChain}`
}

export function canBrowsePrev(s: RevealView): boolean {
  const shown = shownPosition(s)
  return shown !== null && toLinear(shown, s.entriesPerChain) > 0
}

export function canBrowseNext(s: RevealView): boolean {
  const shown = shownPosition(s)
  if (!shown || !s.highWater) return false
  return toLinear(shown, s.entriesPerChain) < toLinear(s.highWater, s.entriesPerChain)
}

/** The host may step back unless the shared cursor is on the very first entry. */
export function canHostPrev(s: RevealView): boolean {
  return s.cursor !== null && toLinear(s.cursor, s.entriesPerChain) > 0
}

/** The shared cursor is on the last entry of the last album: NEXT finishes the game. */
export function isLastPosition(s: RevealView): boolean {
  if (!s.cursor || s.chains === 0) return false
  return toLinear(s.cursor, s.entriesPerChain) === s.chains * s.entriesPerChain - 1
}
