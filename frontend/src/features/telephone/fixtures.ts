// Test fixtures shaped like the payloads `games.telephone.TelephoneViews` emits (api-contracts
// §3.10). Imported by tests only.

import type { Stroke } from '@/features/drawing/types'

import type {
  AlbumDto,
  AlbumRevealedPayload,
  AlbumsResponse,
  RevealEntryDto,
  TelephonePhaseStartedPayload,
  TelephoneProgressPayload,
  TelephoneSnapshot,
  TelephoneStepEndedPayload,
  TelephoneTaskPayload,
} from './types'

export const PHASE_PROMPT = 'phase-tp-prompt'
export const PHASE_DRAW = 'phase-tp-draw'
export const PHASE_DESCRIBE = 'phase-tp-describe'

export const sampleTelephoneStroke: Stroke = {
  strokeId: '7d0f3a52-2b7e-4a6c-9d61-3f5f1c1b0a01',
  tool: 'PENCIL',
  color: '#2B2B2B',
  size: 2,
  points: [
    [1000, 1000],
    [2000, 2500],
    [3200, 2600],
  ],
}

export const promptPhaseStarted: TelephonePhaseStartedPayload = {
  phase: 'PROMPT',
  phaseId: PHASE_PROMPT,
  step: 0,
  totalSteps: 4,
  kind: 'PROMPT',
  deadlineAt: '2099-01-01T00:01:00.000Z',
}

export const drawPhaseStarted: TelephonePhaseStartedPayload = {
  phase: 'DRAW',
  phaseId: PHASE_DRAW,
  step: 1,
  totalSteps: 4,
  kind: 'DRAW',
  deadlineAt: '2099-01-01T00:02:30.000Z',
}

export const describePhaseStarted: TelephonePhaseStartedPayload = {
  phase: 'DESCRIBE',
  phaseId: PHASE_DESCRIBE,
  step: 2,
  totalSteps: 4,
  kind: 'DESCRIBE',
  deadlineAt: '2099-01-01T00:03:15.000Z',
}

export const promptTask: TelephoneTaskPayload = {
  phaseId: PHASE_PROMPT,
  step: 0,
  kind: 'PROMPT',
  source: null,
  deadlineAt: promptPhaseStarted.deadlineAt ?? '',
}

export const drawTask: TelephoneTaskPayload = {
  phaseId: PHASE_DRAW,
  step: 1,
  kind: 'DRAW',
  source: { kind: 'TEXT', text: 'A stegosaurus juggling teacups', placeholder: false },
  deadlineAt: drawPhaseStarted.deadlineAt ?? '',
}

export const describeTask: TelephoneTaskPayload = {
  phaseId: PHASE_DESCRIBE,
  step: 2,
  kind: 'DESCRIBE',
  source: { kind: 'DRAWING', strokes: [sampleTelephoneStroke], placeholder: false },
  deadlineAt: describePhaseStarted.deadlineAt ?? '',
}

export const drawProgress: TelephoneProgressPayload = {
  phaseId: PHASE_DRAW,
  submittedCount: 2,
  total: 4,
  submittedGuestIds: ['guest-2', 'guest-3'],
}

export const drawStepEnded: TelephoneStepEndedPayload = {
  step: 1,
  kind: 'DRAW',
  reason: 'TIME',
  timedOutGuestIds: ['guest-4'],
  leftGuestIds: [],
}

const header = {
  sessionId: 'session-tp-01',
  gameKey: 'telephone',
  status: 'RUNNING' as const,
  order: ['guest-1', 'guest-2', 'guest-3', 'guest-4'],
  phaseStartedAt: '2099-01-01T00:01:00.000Z',
  scores: null,
  players: [
    { guestId: 'guest-1', status: 'ACTIVE' as const },
    { guestId: 'guest-2', status: 'ACTIVE' as const },
    { guestId: 'guest-3', status: 'ACTIVE' as const },
    { guestId: 'guest-4', status: 'ACTIVE' as const },
  ],
  results: null,
  rematch: null,
}

/** A holder mid-DRAW with a server-held draft (reconnect). */
export const drawSnapshotWithDraft: TelephoneSnapshot = {
  ...header,
  phase: 'DRAW',
  phaseId: PHASE_DRAW,
  deadlineAt: drawPhaseStarted.deadlineAt,
  step: 1,
  totalSteps: 4,
  entriesPerChain: 4,
  kind: 'DRAW',
  task: { kind: 'DRAW', source: drawTask.source },
  draft: {
    strokes: [sampleTelephoneStroke],
    canvasBudget: { points: 3, maxPoints: 12000, strokes: 1, maxStrokes: 300 },
  },
  submitted: false,
  submittedGuestIds: ['guest-2'],
  waitingOn: 3,
  reveal: null,
}

/** A holder in DESCRIBE who already submitted: no draft, waiting screen. */
export const describeSnapshotSubmitted: TelephoneSnapshot = {
  ...header,
  phase: 'DESCRIBE',
  phaseId: PHASE_DESCRIBE,
  deadlineAt: describePhaseStarted.deadlineAt,
  step: 2,
  totalSteps: 4,
  entriesPerChain: 4,
  kind: 'DESCRIBE',
  task: { kind: 'DESCRIBE', source: describeTask.source },
  draft: null,
  submitted: true,
  submittedGuestIds: ['guest-1', 'guest-3'],
  waitingOn: 2,
  reveal: null,
}

/** A holder in PROMPT with a half-typed server draft. */
export const promptSnapshotWithDraft: TelephoneSnapshot = {
  ...header,
  phase: 'PROMPT',
  phaseId: PHASE_PROMPT,
  deadlineAt: promptPhaseStarted.deadlineAt,
  step: 0,
  totalSteps: 4,
  entriesPerChain: 4,
  kind: 'PROMPT',
  task: { kind: 'PROMPT', source: null },
  draft: { text: 'A raptor on roller skates' },
  submitted: false,
  submittedGuestIds: [],
  waitingOn: 4,
  reveal: null,
}

// ---------------------------------------------------------------- reveal and albums (P6-03/P6-05)

export const PHASE_REVEAL = 'phase-tp-reveal'
export const REVEAL_SESSION = header.sessionId

/** Chain c is owned by order[c]; entry e of chain c was made by order[(c + e) % 4]. */
export const authorOf = (chain: number, entry: number) => header.order[(chain + entry) % 4]

const NICKNAMES: Record<string, string> = {
  'guest-1': 'Rex',
  'guest-2': 'Trixie',
  'guest-3': 'Ptera',
  'guest-4': 'Steggy',
}

/** The text of a text entry: "Prompt 2" for chain 1 entry 0, "Description 1.3" otherwise. */
export const entryText = (chain: number, entry: number) =>
  entry === 0 ? `Prompt ${chain + 1}` : `Description ${chain + 1}.${entry + 1}`

/** A revealed entry as the server sends it; even entries are text, odd ones drawings. */
export function revealEntry(chain: number, entry: number, omitted = false): RevealEntryDto {
  const author = authorOf(chain, entry)
  const base = {
    entryIndex: entry,
    authorGuestId: author,
    authorNickname: NICKNAMES[author],
    fill: 'NONE' as const,
    timedOut: false,
    placeholder: false,
  }
  return entry % 2 === 0
    ? { ...base, kind: 'TEXT', text: entryText(chain, entry), omitted: false }
    : { ...base, kind: 'DRAWING', strokes: omitted ? null : [sampleTelephoneStroke], omitted }
}

export const revealPhaseStarted: TelephonePhaseStartedPayload = {
  phase: 'REVEAL',
  phaseId: PHASE_REVEAL,
  deadlineAt: null,
  totalSteps: 4,
  chains: 4,
  cursor: { chainIndex: 0, entryIndex: 0 },
  autoAdvanceAt: '2099-01-01T00:20:00.000Z',
}

/** `ALBUM_REVEALED` for (chain, entry); `newly=false` carries no entry. */
export function albumRevealed(
  chain: number,
  entry: number,
  newly = true,
  autoAdvanced = false,
): AlbumRevealedPayload {
  return {
    chainIndex: chain,
    entryIndex: entry,
    newlyRevealed: newly,
    entry: newly ? revealEntry(chain, entry) : null,
    autoAdvanced,
    autoAdvanceAt: '2099-01-01T00:21:00.000Z',
  }
}

/**
 * A reconnect during the reveal: album 1 fully revealed with its first drawing withheld by the
 * byte budget, album 2's prompt shown, cursor back on album 1's last entry.
 */
export const revealSnapshot: TelephoneSnapshot = {
  ...header,
  phase: 'REVEAL',
  phaseId: PHASE_REVEAL,
  deadlineAt: null,
  step: 4,
  totalSteps: 4,
  entriesPerChain: 4,
  kind: null,
  task: null,
  draft: null,
  submitted: false,
  submittedGuestIds: [],
  waitingOn: 0,
  reveal: {
    cursor: { chainIndex: 0, entryIndex: 3 },
    highWater: { chainIndex: 1, entryIndex: 0 },
    autoAdvanceAt: '2099-01-01T00:22:00.000Z',
    chains: 4,
    albums: [
      {
        chainIndex: 0,
        ownerGuestId: 'guest-1',
        entryCount: 4,
        revealedCount: 4,
        entries: [revealEntry(0, 0), revealEntry(0, 1, true), revealEntry(0, 2), revealEntry(0, 3)],
      },
      {
        chainIndex: 1,
        ownerGuestId: 'guest-2',
        entryCount: 4,
        revealedCount: 1,
        entries: [revealEntry(1, 0)],
      },
      { chainIndex: 2, ownerGuestId: 'guest-3', entryCount: 4, revealedCount: 0, entries: [] },
      { chainIndex: 3, ownerGuestId: 'guest-4', entryCount: 4, revealedCount: 0, entries: [] },
    ],
  },
}

export const albumsResponse: AlbumsResponse = {
  sessionId: REVEAL_SESSION,
  complete: true,
  albums: [0, 1, 2, 3].map((chain) => ({
    chainIndex: chain,
    ownerGuestId: header.order[chain],
    entryCount: 4,
    entriesPerChain: 4,
    complete: true,
    hasPlaceholders: chain === 2,
  })),
}

/** `GET /sessions/{id}/albums/{chain}`: chain 2 has a placeholder drawing by a player who left. */
export function albumDto(chain: number): AlbumDto {
  return {
    sessionId: REVEAL_SESSION,
    chainIndex: chain,
    ownerGuestId: header.order[chain],
    entriesPerChain: 4,
    complete: true,
    entries: [0, 1, 2, 3].map((step) => {
      const author = authorOf(chain, step)
      const base = {
        step,
        authorGuestId: author,
        authorNickname: NICKNAMES[author],
        fill: 'NONE' as const,
        timedOut: false,
        placeholder: false,
      }
      if (step % 2 === 0) return { ...base, kind: 'TEXT' as const, text: entryText(chain, step) }
      if (chain === 2 && step === 1) {
        return {
          ...base,
          kind: 'DRAWING' as const,
          strokes: [],
          fill: 'LEFT_PLAYER' as const,
          placeholder: true,
        }
      }
      return { ...base, kind: 'DRAWING' as const, strokes: [sampleTelephoneStroke] }
    }),
  }
}
