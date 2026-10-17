import { beforeEach, describe, expect, it } from 'vitest'

import {
  albumRevealed,
  PHASE_REVEAL,
  REVEAL_SESSION,
  revealPhaseStarted,
  revealSnapshot,
  drawSnapshotWithDraft,
  sampleTelephoneStroke,
} from './fixtures'
import {
  canBrowseNext,
  canBrowsePrev,
  canHostPrev,
  isAlbumComplete,
  isLastPosition,
  positionLabel,
  shownPosition,
  visibleEntries,
} from './revealSelectors'
import { useRevealStore } from './revealStore'
import type { TelephoneSnapshot } from './types'

const store = () => useRevealStore.getState()

function startReveal() {
  store().applyPhaseStarted(revealPhaseStarted, REVEAL_SESSION)
  store().applyAlbumRevealed(albumRevealed(0, 0))
}

/** Host-driven NEXTs from the current cursor, as the server publishes them. */
function revealUpTo(chain: number, entry: number) {
  for (let i = 1; i <= chain * 4 + entry; i++) {
    store().applyAlbumRevealed(albumRevealed(Math.floor(i / 4), i % 4))
  }
}

describe('revealStore', () => {
  beforeEach(() => store().reset())

  it('TP6 starts at the first prompt when the REVEAL phase starts', () => {
    startReveal()

    const s = store()
    expect(s.phaseId).toBe(PHASE_REVEAL)
    expect(s.chains).toBe(4)
    expect(s.entriesPerChain).toBe(4)
    expect(s.cursor).toEqual({ chainIndex: 0, entryIndex: 0 })
    expect(s.highWater).toEqual({ chainIndex: 0, entryIndex: 0 })
    expect(visibleEntries(s).map((e) => e.text)).toEqual(['Prompt 1'])
    expect(positionLabel(s)).toBe('Album 1 of 4 — Step 1 of 4')
  })

  it('TP6 moves the cursor and stores content only from first reveals', () => {
    startReveal()
    store().applyAlbumRevealed(albumRevealed(0, 1))
    store().applyAlbumRevealed(albumRevealed(0, 0, false)) // host stepped back

    const s = store()
    expect(s.cursor).toEqual({ chainIndex: 0, entryIndex: 0 })
    expect(s.highWater).toEqual({ chainIndex: 0, entryIndex: 1 })
    expect(s.entries['0:1']?.strokes).toEqual([sampleTelephoneStroke])
    // the album shows what is revealed up to the shown position, in step order
    expect(visibleEntries(s).map((e) => e.step)).toEqual([0])
  })

  it('crosses album boundaries and knows when an album is complete', () => {
    startReveal()
    revealUpTo(1, 0)

    const s = store()
    expect(positionLabel(s)).toBe('Album 2 of 4 — Step 1 of 4')
    expect(isAlbumComplete(s, 0)).toBe(true)
    expect(isAlbumComplete(s, 1)).toBe(false)
    expect(canHostPrev(s)).toBe(true)
    expect(isLastPosition(s)).toBe(false)
  })

  it('never holds an entry the server did not reveal, even for an unknown position', () => {
    startReveal()
    store().applyAlbumRevealed({ ...albumRevealed(2, 3, false) })

    // a cursor jump without content (a missed event) does not invent entries
    expect(Object.keys(store().entries)).toEqual(['0:0'])
  })

  it('ignores reveal events while no reveal is running', () => {
    store().applyAlbumRevealed(albumRevealed(0, 0))
    expect(store().cursor).toBeNull()
    expect(store().entries).toEqual({})
  })

  it('applies a duplicated event idempotently', () => {
    startReveal()
    store().applyAlbumRevealed(albumRevealed(0, 1))
    const before = store()
    store().applyAlbumRevealed(albumRevealed(0, 1))
    expect(store().entries).toEqual(before.entries)
    expect(store().highWater).toEqual(before.highWater)
  })

  it('TP6 restores the reveal wholesale from a reconnect snapshot, omitted drawings included', () => {
    store().applySnapshot(revealSnapshot)

    const s = store()
    expect(s.phaseId).toBe(PHASE_REVEAL)
    expect(s.sessionId).toBe(REVEAL_SESSION)
    expect(s.cursor).toEqual({ chainIndex: 0, entryIndex: 3 })
    expect(s.highWater).toEqual({ chainIndex: 1, entryIndex: 0 })
    expect(s.autoAdvanceAt).toBe('2099-01-01T00:22:00.000Z')
    expect(Object.keys(s.entries).sort()).toEqual(['0:0', '0:1', '0:2', '0:3', '1:0'])
    expect(s.entries['0:1']).toMatchObject({ omitted: true, strokes: null })
    expect(visibleEntries(s).map((e) => e.step)).toEqual([0, 1, 2, 3])
  })

  it('keeps a drawing it already holds when the snapshot of the same reveal omits it', () => {
    startReveal()
    store().applyAlbumRevealed(albumRevealed(0, 1))
    store().applySnapshot(revealSnapshot)
    expect(store().entries['0:1']).toMatchObject({
      omitted: false,
      strokes: [sampleTelephoneStroke],
    })
  })

  it('fills an omitted drawing when a later event carries it', () => {
    store().applySnapshot(revealSnapshot)
    store().applyAlbumRevealed(albumRevealed(0, 1))
    expect(store().entries['0:1']).toMatchObject({ omitted: false })
  })

  it('clears everything for a snapshot of another phase, another session or no game', () => {
    store().applySnapshot(revealSnapshot)
    store().applySnapshot(drawSnapshotWithDraft)
    expect(store().phaseId).toBeNull()
    expect(store().entries).toEqual({})

    store().applySnapshot(revealSnapshot)
    store().applySnapshot(null)
    expect(store().entries).toEqual({})

    store().applySnapshot(revealSnapshot)
    store().applySnapshot({
      ...revealSnapshot,
      sessionId: 'session-other',
      phaseId: 'phase-other',
      reveal: { ...revealSnapshot.reveal!, albums: [] },
    } as TelephoneSnapshot)
    expect(store().sessionId).toBe('session-other')
    expect(store().entries).toEqual({})
  })

  it('a new phase or session start drops the old reveal (rematch)', () => {
    startReveal()
    revealUpTo(0, 3)
    store().applyPhaseStarted({ ...revealPhaseStarted, phase: 'PROMPT', phaseId: 'p-next' }, 's2')
    expect(store().phaseId).toBeNull()
    expect(store().entries).toEqual({})
  })

  describe('local browsing', () => {
    beforeEach(() => {
      startReveal()
      revealUpTo(1, 1)
    })

    it('TP6 browses revealed entries without moving the shared cursor', () => {
      store().browsePrev()
      store().browsePrev()

      const s = store()
      expect(shownPosition(s)).toEqual({ chainIndex: 0, entryIndex: 3 })
      expect(s.cursor).toEqual({ chainIndex: 1, entryIndex: 1 })
      expect(visibleEntries(s).map((e) => e.step)).toEqual([0, 1, 2, 3])
      expect(positionLabel(s)).toBe('Album 1 of 4 — Step 4 of 4')
    })

    it('cannot browse past the furthest revealed entry and rejoins the live cursor', () => {
      expect(canBrowseNext(store())).toBe(false)
      store().browseNext()
      expect(store().browse).toBeNull()

      store().browsePrev()
      expect(canBrowseNext(store())).toBe(true)
      store().browseNext()
      expect(store().browse).toBeNull() // back on the live position
    })

    it('jumps only to revealed positions', () => {
      store().browseTo({ chainIndex: 0, entryIndex: 2 })
      expect(shownPosition(store())).toEqual({ chainIndex: 0, entryIndex: 2 })

      store().browseTo({ chainIndex: 3, entryIndex: 0 })
      expect(shownPosition(store())).toEqual({ chainIndex: 0, entryIndex: 2 })
    })

    it('stops at the very first entry', () => {
      store().browseTo({ chainIndex: 0, entryIndex: 0 })
      expect(canBrowsePrev(store())).toBe(false)
      store().browsePrev()
      expect(shownPosition(store())).toEqual({ chainIndex: 0, entryIndex: 0 })
    })

    it('keeps the browsed position while the live cursor moves on', () => {
      store().browseTo({ chainIndex: 0, entryIndex: 1 })
      store().applyAlbumRevealed(albumRevealed(1, 2))
      expect(shownPosition(store())).toEqual({ chainIndex: 0, entryIndex: 1 })
      store().followLive()
      expect(shownPosition(store())).toEqual({ chainIndex: 1, entryIndex: 2 })
    })
  })

  describe('host navigation', () => {
    beforeEach(startReveal)

    it('locks further moves until the command is answered', () => {
      store().trackNav('a-1')
      expect(store().pendingActionId).toBe('a-1')
      store().applyActionAck('other')
      expect(store().pendingActionId).toBe('a-1')
      store().applyActionAck('a-1')
      expect(store().pendingActionId).toBeNull()
    })

    it('a move published by the server also settles a pending command', () => {
      store().trackNav('a-1')
      store().applyAlbumRevealed(albumRevealed(0, 1))
      expect(store().pendingActionId).toBeNull()
    })

    it('a stale cursor resyncs; a lost host role resyncs with a notice', () => {
      store().trackNav('a-1')
      expect(
        store().applyCommandError(
          { code: 'STALE_ACTION', message: 'x', retryable: false, actionId: 'a-1' },
          'a-1',
        ),
      ).toBe('resync')
      expect(store().pendingActionId).toBeNull()
      expect(store().navError).toBeNull()

      store().trackNav('a-2')
      expect(
        store().applyCommandError({ code: 'NOT_HOST', message: 'x', retryable: false }, 'a-2'),
      ).toBe('resync')
      expect(store().navError).toMatch(/only the host/i)
    })

    it('leaves errors of commands it did not send to others', () => {
      expect(
        store().applyCommandError({ code: 'STALE_ACTION', message: 'x', retryable: false }, 'zz'),
      ).toBe('unhandled')
    })
  })
})
