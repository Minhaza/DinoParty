import type { PlayerDto, SessionRosterPlayer } from '@/api/types'
import type { Stroke } from '@/features/drawing/types'

import type {
  ImpostorChatLine,
  ImpostorGuessOpenPayload,
  ImpostorRoleInfo,
  ImpostorRolePayload,
  ImpostorRoundEndedPayload,
  ImpostorVoteClosedPayload,
} from '../common/types'
import type {
  SketchyDrawing,
  SketchyGalleryOpenedPayload,
  SketchyGalleryPhaseStarted,
  SketchyGuessPhaseStarted,
  SketchyProgressPayload,
  SketchyRoleRevealPhaseStarted,
  SketchyRoundEndPhaseStarted,
  SketchySketchPhaseStarted,
  SketchySaurusSnapshot,
} from './types'

export const FIXTURE_SESSION_ID = 'session-ss-fixture'
export const FIXTURE_DEADLINE = '2099-01-01T00:00:25.000Z'

export const PHASE_SS_ROLE = 'phase-ss-role-r1'
export const PHASE_SS_SKETCH = 'phase-ss-sketch-r1'
export const PHASE_SS_GALLERY = 'phase-ss-gallery-r1'
export const PHASE_SS_GUESS = 'phase-ss-guess-r1'
export const PHASE_SS_END = 'phase-ss-end-r1'

const NICKNAMES = ['Rexy', 'Trixie', 'Stego', 'Ptera', 'Bronto'] as const
const AVATARS = ['dino-rex', 'dino-tricera', 'dino-stego', 'dino-ptera', 'dino-mite'] as const

export const SS_PLAYERS: PlayerDto[] = NICKNAMES.map((nickname, i) => ({
  guestId: `ss-g${i + 1}`,
  nickname,
  avatarKey: AVATARS[i % AVATARS.length],
  avatarColor: null,
  ready: false,
  connection: 'CONNECTED',
  isHost: i === 0,
  score: null,
}))

export const SS_ROSTER: SessionRosterPlayer[] = SS_PLAYERS.map((p) => ({
  guestId: p.guestId,
  status: 'ACTIVE',
}))

export const VIEWER_ARTIST = 'ss-g1'
export const VIEWER_IMPOSTOR = 'ss-g2'
export const OTHER_PLAYER = 'ss-g3'

export const SS_CATEGORY = { key: 'fossils', label: 'Fossils' }
export const SECRET_WORD = 'Amber'
export const DECOY_WORD = 'Trilobite'

export const ARTIST_ROLE: ImpostorRoleInfo = {
  role: 'ARTIST',
  category: SS_CATEGORY,
  word: SECRET_WORD,
}

export const AWARE_IMPOSTOR_ROLE: ImpostorRoleInfo = {
  role: 'IMPOSTOR',
  category: SS_CATEGORY,
  word: null,
}

export const UNAWARE_IMPOSTOR_ROLE: ImpostorRoleInfo = {
  role: 'ARTIST',
  category: SS_CATEGORY,
  word: DECOY_WORD,
}

export const sampleStroke = (
  _authorGuestId: string,
  strokeId: string,
  color = '#2563EB',
): Stroke => ({
  strokeId,
  tool: 'PENCIL',
  color,
  size: 3,
  points: [
    [1000, 1000],
    [2000, 2000],
    [3000, 2000],
  ],
  closed: true,
})

export const sampleDrawings: SketchyDrawing[] = [
  {
    guestId: 'ss-g1',
    strokes: [sampleStroke('ss-g1', 's1', '#2563EB')],
    empty: false,
  },
  {
    guestId: 'ss-g2',
    strokes: [sampleStroke('ss-g2', 's2', '#DC2626')],
    empty: false,
  },
  {
    guestId: 'ss-g3',
    strokes: [sampleStroke('ss-g3', 's3', '#16A34A')],
    empty: false,
  },
  {
    guestId: 'ss-g4',
    strokes: [],
    empty: true,
  },
  {
    guestId: 'ss-g5',
    strokes: [sampleStroke('ss-g5', 's5', '#9333EA')],
    empty: false,
  },
]

export const roleRevealStarted: SketchyRoleRevealPhaseStarted = {
  phase: 'ROLE_REVEAL',
  phaseId: PHASE_SS_ROLE,
  round: 1,
  totalRounds: 3,
  deadlineAt: FIXTURE_DEADLINE,
}

export const sketchStarted: SketchySketchPhaseStarted = {
  phase: 'SKETCH',
  phaseId: PHASE_SS_SKETCH,
  round: 1,
  totalRounds: 3,
  deadlineAt: FIXTURE_DEADLINE,
}

export const galleryStarted: SketchyGalleryPhaseStarted = {
  phase: 'GALLERY',
  phaseId: PHASE_SS_GALLERY,
  round: 1,
  totalRounds: 3,
  deadlineAt: FIXTURE_DEADLINE,
}

export const guessStarted: SketchyGuessPhaseStarted = {
  phase: 'GUESS',
  phaseId: PHASE_SS_GUESS,
  round: 1,
  totalRounds: 3,
  deadlineAt: FIXTURE_DEADLINE,
  impostorGuestId: VIEWER_IMPOSTOR,
}

export const roundEndStarted: SketchyRoundEndPhaseStarted = {
  phase: 'ROUND_END',
  phaseId: PHASE_SS_END,
  round: 1,
  totalRounds: 3,
  deadlineAt: FIXTURE_DEADLINE,
}

export const sketchyProgress: SketchyProgressPayload = {
  phaseId: PHASE_SS_SKETCH,
  submittedGuestIds: ['ss-g1', 'ss-g3'],
}

export const sketchyGalleryOpened: SketchyGalleryOpenedPayload = {
  round: 1,
  drawings: sampleDrawings,
}

export const voteClosedImpostorCaught: ImpostorVoteClosedPayload = {
  round: 1,
  votes: {
    'ss-g1': 'ss-g2',
    'ss-g2': 'ss-g3',
    'ss-g3': 'ss-g2',
    'ss-g4': 'ss-g2',
    'ss-g5': 'ss-g1',
  },
  accusedGuestId: 'ss-g2',
  caught: true,
  impostorGuestId: 'ss-g2',
}

export const guessOpenFixture: ImpostorGuessOpenPayload = {
  phaseId: PHASE_SS_GUESS,
  round: 1,
  category: SS_CATEGORY,
  deadlineAt: FIXTURE_DEADLINE,
}

export const roundEndedCaughtWrongGuess: ImpostorRoundEndedPayload = {
  round: 1,
  outcome: 'CAUGHT',
  impostorGuestId: 'ss-g2',
  category: SS_CATEGORY,
  word: SECRET_WORD,
  decoyWord: DECOY_WORD,
  votes: voteClosedImpostorCaught.votes,
  guess: 'Bone',
  scoreDeltas: {
    'ss-g1': 100,
    'ss-g3': 100,
    'ss-g4': 100,
    'ss-g2': 0,
    'ss-g5': 0,
  },
  scores: {
    'ss-g1': 500,
    'ss-g2': 400,
    'ss-g3': 400,
    'ss-g4': 300,
    'ss-g5': 100,
  },
}

export const roundEndedEscaped: ImpostorRoundEndedPayload = {
  ...roundEndedCaughtWrongGuess,
  outcome: 'ESCAPED',
  guess: null,
}

/** Caught with `unawareGuess = false`: `GALLERY` goes straight to `ROUND_END`, so there is no guess. */
export const roundEndedCaughtNoGuess: ImpostorRoundEndedPayload = {
  ...roundEndedCaughtWrongGuess,
  guess: null,
}

export const sampleChat: ImpostorChatLine[] = [
  { guestId: 'ss-g1', text: 'Drawing 2 looks very suspicious!', ts: '2099-01-01T00:00:10.000Z' },
  { guestId: 'ss-g2', text: 'Hey, I drew a fossil too!', ts: '2099-01-01T00:00:15.000Z' },
]

export function rolePayload(
  info: ImpostorRoleInfo,
  phaseId = PHASE_SS_ROLE,
  round = 1,
): ImpostorRolePayload {
  return {
    phaseId,
    round,
    role: info.role,
    category: info.category,
    word: info.word,
  }
}

export function sketchySnapshot(
  phase: 'ROLE_REVEAL' | 'SKETCH' | 'GALLERY' | 'GUESS' | 'ROUND_END',
  role: ImpostorRoleInfo | null = ARTIST_ROLE,
  over: Partial<SketchySaurusSnapshot> = {},
): SketchySaurusSnapshot {
  const phaseId =
    phase === 'ROLE_REVEAL'
      ? PHASE_SS_ROLE
      : phase === 'SKETCH'
        ? PHASE_SS_SKETCH
        : phase === 'GALLERY'
          ? PHASE_SS_GALLERY
          : phase === 'GUESS'
            ? PHASE_SS_GUESS
            : PHASE_SS_END

  return {
    sessionId: FIXTURE_SESSION_ID,
    gameKey: 'sketchy-saurus',
    status: 'RUNNING',
    order: SS_PLAYERS.map((p) => p.guestId),
    phase,
    phaseId,
    phaseStartedAt: '2099-01-01T00:00:00.000Z',
    deadlineAt: FIXTURE_DEADLINE,
    scores: Object.fromEntries(SS_PLAYERS.map((p) => [p.guestId, 0])),
    players: SS_ROSTER,
    results: null,
    rematch: null,
    round: 1,
    totalRounds: 3,
    nicknames: Object.fromEntries(SS_PLAYERS.map((p) => [p.guestId, p.nickname])),
    role,
    draft:
      phase === 'SKETCH'
        ? {
            strokes: [sampleStroke(VIEWER_ARTIST, 'draft-s1')],
            canvasBudget: { points: 3, maxPoints: 1500, strokes: 1, maxStrokes: 100 },
          }
        : null,
    submitted: false,
    submittedGuestIds: phase === 'SKETCH' ? ['ss-g1'] : [],
    gallery:
      phase === 'GALLERY' || phase === 'GUESS' || phase === 'ROUND_END' ? sampleDrawings : null,
    votedGuestIds: phase === 'GALLERY' ? ['ss-g1'] : [],
    myVote: null,
    recentChat: phase === 'GALLERY' || phase === 'GUESS' || phase === 'ROUND_END' ? sampleChat : [],
    voteClosed: phase === 'GUESS' || phase === 'ROUND_END' ? voteClosedImpostorCaught : null,
    roundEnd: phase === 'ROUND_END' ? roundEndedCaughtWrongGuess : null,
    ...over,
  }
}

const BIG_NICKNAMES = [...NICKNAMES, 'Anky', 'Raptor', 'Diplo', 'Spino', 'Compy'] as const

/** A full 10-player room (the maximum). */
export const SS_PLAYERS_10: PlayerDto[] = BIG_NICKNAMES.map((nickname, i) => ({
  ...SS_PLAYERS[0],
  guestId: `ss-g${i + 1}`,
  nickname,
  avatarKey: AVATARS[i % AVATARS.length],
  isHost: i === 0,
}))

/** One drawing at the size limit: 100 strokes, 1 500 live points in total ([DG-14]). */
export function maxDrawing(guestId: string, seed: number): SketchyDrawing {
  const strokes: Stroke[] = Array.from({ length: 100 }, (_, i) => ({
    strokeId: `${guestId}-s${i}`,
    tool: 'PENCIL',
    color: ['#2563EB', '#DC2626', '#16A34A', '#9333EA'][(i + seed) % 4],
    size: 2,
    points: Array.from(
      { length: 15 },
      (_, k) =>
        [(i * 90 + k * 40 + seed * 50) % 10000, (k * 600 + i * 70) % 10000] as [number, number],
    ),
    closed: true,
  }))
  return { guestId, strokes, empty: false }
}

/** 10 drawings at the budget limit; the last artist handed in nothing. */
export const maxGallery: SketchyDrawing[] = SS_PLAYERS_10.map((p, i) =>
  i === SS_PLAYERS_10.length - 1
    ? { guestId: p.guestId, strokes: [], empty: true }
    : maxDrawing(p.guestId, i),
)

export const SS_ROSTER_10: SessionRosterPlayer[] = SS_PLAYERS_10.map((p) => ({
  guestId: p.guestId,
  status: 'ACTIVE',
}))
