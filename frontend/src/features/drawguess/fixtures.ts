import type {
  DrawGuessSnapshot,
  DrawingStrokeUpdate,
  DrawingUndoUpdate,
  DrawingClearUpdate,
  HintUpdatedPayload,
  GuessAcceptedPayload,
  GuessChatPayload,
  GuesserChatPayload,
  RoundEndedPayload,
  WordChoicesPayload,
  DrawerWordPayload,
  GuessFeedbackPayload,
  DrawGuessPhaseStartedPayload,
  Stroke,
} from './types'

export const sampleStroke: Stroke = {
  strokeId: '550e8400-e29b-41d4-a716-446655440000',
  tool: 'PENCIL',
  color: '#2B2B2B',
  size: 2,
  points: [
    [1000, 1000],
    [1200, 1100],
    [1500, 1300],
  ],
  closed: true,
}

export const sampleEraserStroke: Stroke = {
  strokeId: '660e8400-e29b-41d4-a716-446655440001',
  tool: 'ERASER',
  color: '#000000', // ignored for eraser
  size: 3,
  points: [
    [1100, 1050],
    [1300, 1150],
  ],
  closed: true,
}

export const sampleWordChoicesPayload: WordChoicesPayload = {
  phaseId: 'phase-wc-01',
  choices: ['VELOCIRAPTOR', 'VOLCANO', 'FOSSIL'],
  deadlineAt: '2026-10-14T10:00:15.000Z',
}

export const sampleDrawerWordPayload: DrawerWordPayload = {
  phaseId: 'phase-dr-01',
  word: 'VOLCANO',
}

export const sampleDrawingStrokeUpdate: DrawingStrokeUpdate = {
  kind: 'STROKE',
  strokeId: '550e8400-e29b-41d4-a716-446655440000',
  batchNo: 0,
  tool: 'PENCIL',
  color: '#2B2B2B',
  size: 2,
  points: [
    [1000, 1000],
    [1200, 1100],
  ],
  final: false,
}

export const sampleDrawingStrokeFinalUpdate: DrawingStrokeUpdate = {
  kind: 'STROKE',
  strokeId: '550e8400-e29b-41d4-a716-446655440000',
  batchNo: 1,
  tool: 'PENCIL',
  color: '#2B2B2B',
  size: 2,
  points: [[1500, 1300]],
  final: true,
}

export const sampleDrawingUndoUpdate: DrawingUndoUpdate = {
  kind: 'UNDO',
  strokeId: '550e8400-e29b-41d4-a716-446655440000',
}

export const sampleDrawingClearUpdate: DrawingClearUpdate = {
  kind: 'CLEAR',
}

export const sampleHintUpdatedPayload: HintUpdatedPayload = {
  hint: 'V___A_O',
}

export const sampleGuessAcceptedPayload: GuessAcceptedPayload = {
  guestId: 'guest-2',
  scoreDelta: 450,
  totalScore: 950,
  order: 0,
}

export const sampleGuessChatPayload: GuessChatPayload = {
  guestId: 'guest-3',
  text: 'mountain',
}

export const sampleGuesserChatPayload: GuesserChatPayload = {
  guestId: 'guest-2',
  text: 'nice drawing!',
}

export const sampleGuessFeedbackClose: GuessFeedbackPayload = {
  result: 'CLOSE',
}

export const sampleGuessFeedbackDuplicate: GuessFeedbackPayload = {
  result: 'DUPLICATE',
}

export const sampleRoundEndedPayload: RoundEndedPayload = {
  round: 1,
  drawerGuestId: 'guest-1',
  word: 'VOLCANO',
  reason: 'ALL_GUESSED',
  scoreDeltas: {
    'guest-1': 500,
    'guest-2': 450,
    'guest-3': 300,
  },
  scores: {
    'guest-1': 500,
    'guest-2': 950,
    'guest-3': 300,
  },
}

export const samplePhaseStartedWordChoice: DrawGuessPhaseStartedPayload = {
  phase: 'WORD_CHOICE',
  phaseId: 'phase-wc-01',
  round: 1,
  totalRounds: 6,
  drawerGuestId: 'guest-1',
  deadlineAt: '2026-10-14T10:00:15.000Z',
}

export const samplePhaseStartedDrawing: DrawGuessPhaseStartedPayload = {
  phase: 'DRAWING',
  phaseId: 'phase-dr-01',
  round: 1,
  totalRounds: 6,
  drawerGuestId: 'guest-1',
  hint: '_______',
  deadlineAt: '2026-10-14T10:01:15.000Z',
}

export const samplePhaseStartedRoundEnd: DrawGuessPhaseStartedPayload = {
  phase: 'ROUND_END',
  phaseId: 'phase-re-01',
  round: 1,
  totalRounds: 6,
  drawerGuestId: 'guest-1',
  deadlineAt: '2026-10-14T10:01:21.000Z',
}

export const sampleDrawingSnapshotDrawer: DrawGuessSnapshot = {
  sessionId: 'session-dg-01',
  gameKey: 'draw-guess',
  status: 'RUNNING',
  order: ['guest-1', 'guest-2', 'guest-3'],
  phase: 'DRAWING',
  phaseId: 'phase-dr-01',
  phaseStartedAt: '2026-10-14T10:00:15.000Z',
  deadlineAt: '2026-10-14T10:01:15.000Z',
  scores: {
    'guest-1': 0,
    'guest-2': 500,
    'guest-3': 0,
  },
  players: [
    { guestId: 'guest-1', status: 'ACTIVE' },
    { guestId: 'guest-2', status: 'ACTIVE' },
    { guestId: 'guest-3', status: 'ACTIVE' },
  ],
  results: null,
  rematch: null,
  round: 1,
  totalRounds: 6,
  drawerGuestId: 'guest-1',
  hint: '_______',
  word: 'VOLCANO',
  wordChoices: null,
  strokes: [sampleStroke],
  guessedGuestIds: ['guest-2'],
  recentChat: [
    { guestId: 'guest-3', text: 'mountain', scope: 'PUBLIC', ts: '2026-10-14T10:00:30.000Z' },
    {
      guestId: 'guest-2',
      text: 'nice drawing!',
      scope: 'GUESSERS',
      ts: '2026-10-14T10:00:35.000Z',
    },
  ],
  canvasBudget: { points: 3, maxPoints: 12000, strokes: 1, maxStrokes: 300 },
  roundEnd: null,
}

export const sampleDrawingSnapshotGuesser: DrawGuessSnapshot = {
  ...sampleDrawingSnapshotDrawer,
  word: null, // guessers do not see the word
}
