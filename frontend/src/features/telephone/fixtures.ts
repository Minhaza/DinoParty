// Test fixtures shaped like the payloads `games.telephone.TelephoneViews` emits (api-contracts
// §3.10). Imported by tests only.

import type { Stroke } from '@/features/drawing/types'

import type {
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
