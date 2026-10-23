// Fixtures shaped like the Dino Impostor payloads of api-contracts §3.11 (the backend is built in
// parallel, P9-02/P9-03). Imported by tests and the DEV-only preview page; never by app code, so
// none of this reaches the production bundle.

import type { PlayerDto, SessionRosterPlayer } from '@/api/types'

import type {
  DinoImpostorDrawingPhaseStarted,
  DinoImpostorGuessPhaseStarted,
  DinoImpostorOtherPhaseStarted,
  DinoImpostorRoleRevealPhaseStarted,
  DinoImpostorSnapshot,
  ImpostorChatLine,
  ImpostorDrawingUpdatedPayload,
  ImpostorGuessOpenPayload,
  ImpostorOutcome,
  ImpostorRoleInfo,
  ImpostorRolePayload,
  ImpostorRoundEndedPayload,
  ImpostorStroke,
  ImpostorVoteClosedPayload,
} from '../common/types'

export const FIXTURE_SESSION_ID = 'session-di-fixture'
export const FIXTURE_DEADLINE = '2099-01-01T00:00:25.000Z'

export const PHASE_DI_ROLE = 'phase-di-role-r2'
export const PHASE_DI_TURN = (turn: number) => `phase-di-turn-r2-t${turn}`
export const PHASE_DI_VOTE = 'phase-di-vote-r2'
export const PHASE_DI_GUESS = 'phase-di-guess-r2'
export const PHASE_DI_END = 'phase-di-end-r2'

const NICKNAMES = [
  'Rexy',
  'Trixie',
  'Stego',
  'Ptera',
  'Bronto',
  'Ankie',
  'Rapto',
  'Spinny',
  'Pachy',
  'Iggy',
] as const
const AVATARS = [
  'dino-rex',
  'dino-tricera',
  'dino-stego',
  'dino-ptera',
  'dino-mite',
  'dino-lizard',
] as const

/** Ten players; `di-g1` is the usual viewer (an artist) and `di-g4` the impostor. */
export const DI_PLAYERS: PlayerDto[] = NICKNAMES.map((nickname, i) => ({
  guestId: `di-g${i + 1}`,
  nickname,
  avatarKey: AVATARS[i % AVATARS.length],
  avatarColor: null,
  ready: false,
  connection: 'CONNECTED',
  isHost: i === 0,
  score: null,
}))
export const DI_PLAYERS_4 = DI_PLAYERS.slice(0, 4)

export const VIEWER_ARTIST = 'di-g1'
export const VIEWER_IMPOSTOR = 'di-g4'
/** In the room but not on the session roster (joined late). */
export const VIEWER_SPECTATOR = 'di-spectator'

/** A colour-blind-friendly fixture palette (the server owns the real one, D56). */
export const DI_PALETTE = [
  '#0072B2',
  '#E69F00',
  '#009E73',
  '#CC79A7',
  '#D55E00',
  '#56B4E9',
  '#7A4FA3',
  '#2F2F2F',
  '#A6761D',
  '#8C564B',
] as const

const ids = (players: PlayerDto[]) => players.map((p) => p.guestId)

export function colorsFor(players: PlayerDto[]): Record<string, string> {
  return Object.fromEntries(players.map((p, i) => [p.guestId, DI_PALETTE[i % DI_PALETTE.length]]))
}

/** Seat order of the round: shuffled, impostor never first ([DI-5]). */
export function seatOrderFor(players: PlayerDto[]): string[] {
  const order = ids(players)
  return [...order.slice(1), order[0]]
}

export const category = { key: 'animals', label: 'Animals' }
export const SECRET_WORD = 'Turtle'

export const ARTIST_ROLE: ImpostorRoleInfo = { role: 'ARTIST', category, word: SECRET_WORD }
export const IMPOSTOR_ROLE: ImpostorRoleInfo = { role: 'IMPOSTOR', category, word: null }

export function rolePayload(role: ImpostorRoleInfo): ImpostorRolePayload {
  return { ...role, phaseId: PHASE_DI_ROLE, round: 2 }
}

/** A deterministic wavy stroke by `author`, the `index`-th on the canvas. */
export function fixtureStroke(
  author: string,
  index: number,
  colors: Record<string, string>,
  closed = true,
): ImpostorStroke {
  const y = 1200 + ((index * 1700) % 7000)
  const x0 = 800 + ((index * 2300) % 5000)
  const points: [number, number][] = Array.from({ length: 12 }, (_, k) => [
    Math.min(10000, x0 + k * 300),
    Math.min(10000, Math.max(0, y + Math.round(Math.sin((k + index) / 2) * 600))),
  ])
  return {
    strokeId: `stroke-${index.toString().padStart(2, '0')}-${author}`,
    tool: 'PENCIL',
    color: colors[author] ?? '#2F2F2F',
    size: 2,
    points,
    closed,
    authorGuestId: author,
  }
}

function roster(players: PlayerDto[]): SessionRosterPlayer[] {
  return players.map((p) => ({ guestId: p.guestId, status: 'ACTIVE' }))
}

interface SnapshotOptions {
  players?: PlayerDto[]
  phase: DinoImpostorSnapshot['phase']
  phaseId: string
  role?: ImpostorRoleInfo | null
  strokeCount?: number
  overrides?: Partial<DinoImpostorSnapshot>
}

export function dinoSnapshot({
  players = DI_PLAYERS,
  phase,
  phaseId,
  role = ARTIST_ROLE,
  strokeCount = 0,
  overrides = {},
}: SnapshotOptions): DinoImpostorSnapshot {
  const colors = colorsFor(players)
  const seatOrder = seatOrderFor(players)
  const strokes = Array.from({ length: strokeCount }, (_, i) =>
    fixtureStroke(seatOrder[i % seatOrder.length], i, colors),
  )
  const points = strokes.reduce((n, s) => n + s.points.length, 0)
  return {
    sessionId: FIXTURE_SESSION_ID,
    gameKey: 'dino-impostor',
    status: 'RUNNING',
    order: ids(players),
    phase,
    phaseId,
    phaseStartedAt: '2099-01-01T00:00:00.000Z',
    deadlineAt: FIXTURE_DEADLINE,
    scores: Object.fromEntries(ids(players).map((id) => [id, 0])),
    players: roster(players),
    results: null,
    rematch: null,
    round: 2,
    totalRounds: players.length,
    laps: 2,
    seatOrder,
    colors,
    nicknames: Object.fromEntries(players.map((p) => [p.guestId, p.nickname])),
    lap: null,
    turn: null,
    totalTurns: 2 * players.length,
    drawerGuestId: null,
    turnStroke: null,
    role,
    strokes,
    canvasBudget: { points, maxPoints: 12000, strokes: strokes.length, maxStrokes: 300 },
    votedGuestIds: [],
    myVote: null,
    recentChat: [],
    voteClosed: null,
    roundEnd: null,
    ...overrides,
  }
}

const drawingAt = (turn: number, players = DI_PLAYERS) => {
  const seatOrder = seatOrderFor(players)
  return {
    lap: Math.floor(turn / players.length) + 1,
    turn,
    drawerGuestId: seatOrder[turn % seatOrder.length],
  }
}

// ROLE_REVEAL

export const roleRevealArtist = dinoSnapshot({ phase: 'ROLE_REVEAL', phaseId: PHASE_DI_ROLE })
export const roleRevealImpostor = dinoSnapshot({
  phase: 'ROLE_REVEAL',
  phaseId: PHASE_DI_ROLE,
  role: IMPOSTOR_ROLE,
})
export const roleRevealSpectator = dinoSnapshot({
  phase: 'ROLE_REVEAL',
  phaseId: PHASE_DI_ROLE,
  role: null,
})

// DRAWING: seat order is g2…g10, g1; the artist viewer g1 draws last in each lap.

const MY_TURN = DI_PLAYERS.length - 1
export const drawingMyTurnArtist = dinoSnapshot({
  phase: 'DRAWING',
  phaseId: PHASE_DI_TURN(MY_TURN),
  strokeCount: MY_TURN,
  overrides: drawingAt(MY_TURN),
})
export const drawingOtherTurnArtist = dinoSnapshot({
  phase: 'DRAWING',
  phaseId: PHASE_DI_TURN(3),
  strokeCount: 3,
  overrides: drawingAt(3),
})
export const drawingOtherTurnImpostor = dinoSnapshot({
  phase: 'DRAWING',
  phaseId: PHASE_DI_TURN(3),
  role: IMPOSTOR_ROLE,
  strokeCount: 3,
  overrides: drawingAt(3),
})
export const drawingSpectator = dinoSnapshot({
  phase: 'DRAWING',
  phaseId: PHASE_DI_TURN(3),
  role: null,
  strokeCount: 3,
  overrides: drawingAt(3),
})
/** Last turn of lap 2 with 10 players: nineteen strokes on the canvas, the drawer's open. */
export const drawingFullCanvas = (() => {
  const snap = dinoSnapshot({
    phase: 'DRAWING',
    phaseId: PHASE_DI_TURN(19),
    strokeCount: 19,
    overrides: drawingAt(19),
  })
  const last = snap.strokes[snap.strokes.length - 1]
  return { ...snap, strokes: [...snap.strokes.slice(0, -1), { ...last, closed: false }] }
})()
/** A four-player game on its first turn. */
export const drawingFourPlayers = dinoSnapshot({
  players: DI_PLAYERS_4,
  phase: 'DRAWING',
  phaseId: PHASE_DI_TURN(0),
  overrides: drawingAt(0, DI_PLAYERS_4),
})

// VOTE

export const sampleChat: ImpostorChatLine[] = [
  {
    guestId: 'di-g2',
    text: 'That wobbly shell line was very suspicious 👀',
    ts: '2099-01-01T00:00:03.000Z',
  },
  {
    guestId: 'di-g4',
    text: 'I drew a leg! Legs are a thing animals have.',
    ts: '2099-01-01T00:00:05.000Z',
  },
  { guestId: 'di-g7', text: '<b>not bold</b> — just text', ts: '2099-01-01T00:00:08.000Z' },
]

const voteOverrides = (myVote: string | null): Partial<DinoImpostorSnapshot> => ({
  votedGuestIds: ['di-g2', 'di-g5', ...(myVote ? [VIEWER_ARTIST] : [])],
  myVote,
  recentChat: sampleChat,
})

export const voteArtist = dinoSnapshot({
  phase: 'VOTE',
  phaseId: PHASE_DI_VOTE,
  strokeCount: 20,
  overrides: voteOverrides('di-g4'),
})
export const voteImpostor = dinoSnapshot({
  phase: 'VOTE',
  phaseId: PHASE_DI_VOTE,
  role: IMPOSTOR_ROLE,
  strokeCount: 20,
  overrides: { ...voteOverrides(null), myVote: null },
})
export const voteSpectator = dinoSnapshot({
  phase: 'VOTE',
  phaseId: PHASE_DI_VOTE,
  role: null,
  strokeCount: 20,
  overrides: { ...voteOverrides(null), myVote: null },
})

// Vote result and GUESS

export const voteClosedCaught: ImpostorVoteClosedPayload = {
  round: 2,
  votes: {
    'di-g1': 'di-g4',
    'di-g2': 'di-g4',
    'di-g3': 'di-g4',
    'di-g4': 'di-g7',
    'di-g5': 'di-g4',
    'di-g6': 'di-g9',
    'di-g7': 'di-g4',
  },
  accusedGuestId: 'di-g4',
  caught: true,
  impostorGuestId: 'di-g4',
}

export const voteClosedEscaped: ImpostorVoteClosedPayload = {
  round: 2,
  votes: { 'di-g1': 'di-g5', 'di-g2': 'di-g4', 'di-g4': 'di-g5', 'di-g6': 'di-g4' },
  accusedGuestId: null,
  caught: false,
  impostorGuestId: null,
}

export const guessAsImpostor = dinoSnapshot({
  phase: 'GUESS',
  phaseId: PHASE_DI_GUESS,
  role: IMPOSTOR_ROLE,
  strokeCount: 20,
  overrides: { recentChat: sampleChat, voteClosed: voteClosedCaught },
})
export const guessAsArtist = dinoSnapshot({
  phase: 'GUESS',
  phaseId: PHASE_DI_GUESS,
  strokeCount: 20,
  overrides: { recentChat: sampleChat, voteClosed: voteClosedCaught },
})
export const guessSpectator = dinoSnapshot({
  phase: 'GUESS',
  phaseId: PHASE_DI_GUESS,
  role: null,
  strokeCount: 20,
  overrides: { recentChat: sampleChat, voteClosed: voteClosedCaught },
})

// ROUND_END, one per outcome ([DI-12] points)

const artists = DI_PLAYERS.map((p) => p.guestId).filter((id) => id !== VIEWER_IMPOSTOR)

export function roundEndedPayload(outcome: ImpostorOutcome): ImpostorRoundEndedPayload {
  const votes = outcome === 'ESCAPED' ? voteClosedEscaped.votes : voteClosedCaught.votes
  const deltas: Record<string, number> = Object.fromEntries(DI_PLAYERS.map((p) => [p.guestId, 0]))
  if (outcome === 'ESCAPED' || outcome === 'GUESSED') deltas[VIEWER_IMPOSTOR] = 2
  if (outcome === 'CAUGHT') {
    for (const id of artists) deltas[id] = 1 + (votes[id] === VIEWER_IMPOSTOR ? 1 : 0)
  }
  return {
    round: 2,
    outcome,
    impostorGuestId: VIEWER_IMPOSTOR,
    category,
    word: SECRET_WORD,
    decoyWord: null,
    votes: outcome === 'IMPOSTOR_LEFT' ? {} : votes,
    guess: outcome === 'GUESSED' ? 'turtle' : outcome === 'CAUGHT' ? 'Tortoise' : null,
    scoreDeltas: deltas,
    scores: Object.fromEntries(Object.entries(deltas).map(([id, d], i) => [id, d + (i % 3)])),
  }
}

export const roundEndSnapshot = (
  outcome: ImpostorOutcome,
  role: ImpostorRoleInfo | null = ARTIST_ROLE,
) =>
  dinoSnapshot({
    phase: 'ROUND_END',
    phaseId: PHASE_DI_END,
    role,
    strokeCount: 20,
    overrides: {
      recentChat: sampleChat,
      voteClosed:
        outcome === 'ESCAPED'
          ? voteClosedEscaped
          : outcome === 'IMPOSTOR_LEFT'
            ? null
            : voteClosedCaught,
      roundEnd: roundEndedPayload(outcome),
    },
  })

// PHASE_STARTED payloads and events (one round as one viewer sees it)

export const roleRevealStarted: DinoImpostorRoleRevealPhaseStarted = {
  phase: 'ROLE_REVEAL',
  phaseId: PHASE_DI_ROLE,
  round: 2,
  totalRounds: 10,
  deadlineAt: '2099-01-01T00:00:05.000Z',
  seatOrder: seatOrderFor(DI_PLAYERS),
  colors: colorsFor(DI_PLAYERS),
}

export function drawingStarted(
  turn: number,
  players = DI_PLAYERS,
): DinoImpostorDrawingPhaseStarted {
  return {
    phase: 'DRAWING',
    phaseId: PHASE_DI_TURN(turn),
    round: 2,
    totalRounds: players.length,
    deadlineAt: FIXTURE_DEADLINE,
    laps: 2,
    totalTurns: 2 * players.length,
    seatOrder: seatOrderFor(players),
    ...drawingAt(turn, players),
  }
}

export const voteStarted: DinoImpostorOtherPhaseStarted = {
  phase: 'VOTE',
  phaseId: PHASE_DI_VOTE,
  round: 2,
  totalRounds: 10,
  deadlineAt: '2099-01-01T00:01:00.000Z',
}

export const guessStarted: DinoImpostorGuessPhaseStarted = {
  phase: 'GUESS',
  phaseId: PHASE_DI_GUESS,
  round: 2,
  totalRounds: 10,
  deadlineAt: '2099-01-01T00:00:30.000Z',
  impostorGuestId: VIEWER_IMPOSTOR,
}

export const roundEndStarted: DinoImpostorOtherPhaseStarted = {
  phase: 'ROUND_END',
  phaseId: PHASE_DI_END,
  round: 2,
  totalRounds: 10,
  deadlineAt: '2099-01-01T00:00:08.000Z',
}

export const guessOpenEvent: ImpostorGuessOpenPayload = {
  phaseId: PHASE_DI_GUESS,
  round: 2,
  category,
  deadlineAt: '2099-01-01T00:00:30.000Z',
}

/** The batches of one live stroke by `author` (three batches, the last final). */
export function strokeBatches(
  author: string,
  strokeId = `live-stroke-${author}`,
  color = colorsFor(DI_PLAYERS)[author],
): ImpostorDrawingUpdatedPayload[] {
  const pts: [number, number][][] = [
    [
      [2000, 2000],
      [2400, 2600],
    ],
    [
      [2800, 3000],
      [3300, 3100],
    ],
    [[3800, 2900]],
  ]
  return pts.map((points, batchNo) => ({
    kind: 'STROKE',
    authorGuestId: author,
    strokeId,
    batchNo,
    tool: 'PENCIL',
    color,
    size: 2,
    points,
    final: batchNo === pts.length - 1,
  }))
}
