import { create } from 'zustand'

import type { GameErrorPayload } from '@/api/types'

import { revealNavErrorCopy } from './copy'
import { entryKey, fromLinear, toLinear } from './revealSelectors'
import type { CommandErrorOutcome } from './telephoneStore'
import type {
  AlbumEntryView,
  AlbumRevealedPayload,
  RevealCursor,
  RevealEntryDto,
  TelephonePhaseStartedPayload,
  TelephoneSnapshot,
} from './types'

/**
 * The live album reveal ([TP-6], api-contracts §3.10). The server owns the shared cursor; this store
 * mirrors it and holds the one client-side copy of every entry received through `ALBUM_REVEALED`
 * or a reveal snapshot. Nothing past `highWater` ever exists here, because the server never sends
 * it. Local browsing (`browse`) is kept apart from the shared cursor and never sent anywhere.
 *
 * Results after the game are read from the album endpoints instead (TanStack Query), and this
 * store is reset when the reveal ends, so the two never hold the same album at once.
 */
export interface RevealState {
  /** The REVEAL phase this state belongs to; null when no reveal is running. */
  phaseId: string | null
  sessionId: string | null
  chains: number
  entriesPerChain: number
  cursor: RevealCursor | null
  highWater: RevealCursor | null
  /** Display only: the server moves the cursor itself when it is due. */
  autoAdvanceAt: string | null
  lastAutoAdvanced: boolean
  /** Keyed `chain:entry`; drawings withheld by a snapshot budget are kept as `omitted`. */
  entries: Record<string, AlbumEntryView>
  /** A locally browsed position; null follows the shared cursor. */
  browse: RevealCursor | null
  /** The host's move in flight: further moves wait for its answer or the next reveal event. */
  pendingActionId: string | null
  navError: string | null

  applyPhaseStarted: (payload: TelephonePhaseStartedPayload, sessionId: string | null) => void
  applyAlbumRevealed: (payload: AlbumRevealedPayload) => void
  applySnapshot: (snapshot: TelephoneSnapshot | null) => void
  browsePrev: () => void
  browseNext: () => void
  browseTo: (position: RevealCursor) => void
  followLive: () => void
  trackNav: (actionId: string) => void
  applyActionAck: (actionId: string | null | undefined) => void
  applyCommandError: (payload: GameErrorPayload, actionId?: string | null) => CommandErrorOutcome
  /** Gives up waiting for an unanswered move so the controls unlock. */
  expireNav: () => void
  reset: () => void
}

const initialState = {
  phaseId: null,
  sessionId: null,
  chains: 0,
  entriesPerChain: 0,
  cursor: null,
  highWater: null,
  autoAdvanceAt: null,
  lastAutoAdvanced: false,
  entries: {} as Record<string, AlbumEntryView>,
  browse: null,
  pendingActionId: null,
  navError: null,
}

/** The shared album view model of a revealed entry. */
export function viewOfRevealEntry(entry: RevealEntryDto): AlbumEntryView {
  return {
    step: entry.entryIndex,
    kind: entry.kind,
    authorGuestId: entry.authorGuestId,
    authorNickname: entry.authorNickname ?? null,
    text: entry.kind === 'TEXT' ? entry.text : null,
    strokes: entry.kind === 'DRAWING' ? entry.strokes : null,
    omitted: entry.kind === 'DRAWING' && (entry.omitted || entry.strokes === null),
    fill: entry.fill,
    timedOut: entry.timedOut,
    placeholder: entry.placeholder,
  }
}

const later = (a: RevealCursor, b: RevealCursor | null, perChain: number) =>
  b === null || toLinear(a, perChain) > toLinear(b, perChain) ? a : b

export const useRevealStore = create<RevealState>((set, get) => ({
  ...initialState,

  applyPhaseStarted: (payload, sessionId) => {
    if (payload.phase !== 'REVEAL') {
      if (get().phaseId !== null) set(initialState)
      return
    }
    const cursor = payload.cursor ?? { chainIndex: 0, entryIndex: 0 }
    set({
      ...initialState,
      phaseId: payload.phaseId,
      sessionId,
      chains: payload.chains ?? 0,
      entriesPerChain: payload.totalSteps ?? 0,
      cursor,
      highWater: cursor,
      autoAdvanceAt: payload.autoAdvanceAt ?? null,
    })
  },

  applyAlbumRevealed: (payload) => {
    const state = get()
    if (state.phaseId === null || state.entriesPerChain === 0) return
    const cursor = { chainIndex: payload.chainIndex, entryIndex: payload.entryIndex }
    const entries = payload.entry
      ? {
          ...state.entries,
          [entryKey(cursor.chainIndex, payload.entry.entryIndex)]: viewOfRevealEntry(payload.entry),
        }
      : state.entries
    set({
      cursor,
      highWater: later(cursor, state.highWater, state.entriesPerChain),
      entries,
      autoAdvanceAt: payload.autoAdvanceAt,
      lastAutoAdvanced: payload.autoAdvanced,
      pendingActionId: null,
      navError: null,
    })
  },

  applySnapshot: (snapshot) => {
    if (!snapshot || snapshot.phase !== 'REVEAL' || !snapshot.reveal) {
      set(initialState)
      return
    }
    const state = get()
    const reveal = snapshot.reveal
    const samePhase = state.phaseId === snapshot.phaseId
    const entries: Record<string, AlbumEntryView> = {}
    for (const album of reveal.albums) {
      for (const dto of album.entries) {
        const key = entryKey(album.chainIndex, dto.entryIndex)
        const view = viewOfRevealEntry(dto)
        // An entry never changes once revealed: a drawing held from an earlier event of the same
        // reveal stays, even when this snapshot withholds its strokes for size.
        const held = samePhase ? state.entries[key] : undefined
        entries[key] = view.omitted && held && !held.omitted ? held : view
      }
    }
    const perChain = snapshot.entriesPerChain
    const highWaterLinear = toLinear(reveal.highWater, perChain)
    const browse =
      samePhase && state.browse && toLinear(state.browse, perChain) <= highWaterLinear
        ? state.browse
        : null
    set({
      ...initialState,
      phaseId: snapshot.phaseId,
      sessionId: snapshot.sessionId,
      chains: reveal.chains,
      entriesPerChain: perChain,
      cursor: reveal.cursor,
      highWater: reveal.highWater,
      autoAdvanceAt: reveal.autoAdvanceAt,
      entries,
      browse,
    })
  },

  browsePrev: () => {
    const s = get()
    const shown = s.browse ?? s.cursor
    if (!shown) return
    const linear = toLinear(shown, s.entriesPerChain)
    if (linear === 0) return
    s.browseTo(fromLinear(linear - 1, s.entriesPerChain))
  },

  browseNext: () => {
    const s = get()
    const shown = s.browse ?? s.cursor
    if (!shown || !s.highWater) return
    const linear = toLinear(shown, s.entriesPerChain)
    if (linear >= toLinear(s.highWater, s.entriesPerChain)) {
      set({ browse: null })
      return
    }
    s.browseTo(fromLinear(linear + 1, s.entriesPerChain))
  },

  browseTo: (position) => {
    const s = get()
    if (!s.highWater || !s.cursor) return
    const linear = toLinear(position, s.entriesPerChain)
    if (
      position.entryIndex < 0 ||
      position.entryIndex >= s.entriesPerChain ||
      linear < 0 ||
      linear > toLinear(s.highWater, s.entriesPerChain)
    ) {
      return
    }
    // Arriving on the live position means following it again.
    set({ browse: linear === toLinear(s.cursor, s.entriesPerChain) ? null : position })
  },

  followLive: () => set({ browse: null }),

  trackNav: (actionId) => set({ pendingActionId: actionId, navError: null }),

  applyActionAck: (actionId) => {
    if (actionId && actionId === get().pendingActionId) set({ pendingActionId: null })
  },

  applyCommandError: (payload, envelopeActionId) => {
    const actionId = payload.actionId ?? envelopeActionId
    if (!actionId || actionId !== get().pendingActionId) return 'unhandled'
    switch (payload.code) {
      case 'STALE_ACTION':
      case 'INVALID_PHASE':
        // Someone (the auto-advance, a double click) moved first; the server's state wins.
        set({ pendingActionId: null })
        return 'resync'
      case 'NOT_HOST':
        set({ pendingActionId: null, navError: revealNavErrorCopy(payload.code) })
        return 'resync'
      default:
        set({ pendingActionId: null, navError: revealNavErrorCopy(payload.code) })
        return 'handled'
    }
  },

  expireNav: () => {
    if (get().pendingActionId !== null) set({ pendingActionId: null })
  },

  reset: () => set(initialState),
}))
