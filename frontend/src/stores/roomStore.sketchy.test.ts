import { beforeEach, describe, expect, it } from 'vitest'

import type { EventEnvelope, GameSnapshot, RoomDto, SnapshotDto } from '@/api/types'
import { useDrawGuessStore } from '@/features/drawguess/drawGuessStore'
import { useDinoImpostorStore } from '@/features/impostor/dino/dinoImpostorStore'
import {
  ARTIST_ROLE,
  galleryStarted,
  guessOpenFixture,
  guessStarted,
  roundEndedCaughtWrongGuess,
  roundEndStarted,
  sampleChat,
  sampleDrawings,
  sketchyProgress,
  sketchySnapshot,
  SS_PLAYERS,
  voteClosedImpostorCaught,
} from '@/features/impostor/sketchy/fixtures'
import { useSketchySaurusStore } from '@/features/impostor/sketchy/sketchySaurusStore'
import { useGameSessionStore } from './gameSessionStore'
import { useRoomStore } from './roomStore'

const room: RoomDto = {
  id: 'room-ss',
  code: 'SKETCH01',
  inviteUrl: 'http://localhost/join/SKETCH01',
  status: 'PLAYING',
  gameKey: 'sketchy-saurus',
  hostGuestId: 'ss-g1',
  minPlayers: 4,
  maxPlayers: 10,
  settings: {},
  players: SS_PLAYERS,
  sessionId: 'session-ss-fixture',
  createdAt: '2026-10-22T10:00:00.000Z',
}

let roomSeq = 0
let privateSeq = 0
const envelope = (
  stream: 'ROOM' | 'PRIVATE',
  type: string,
  payload: unknown,
  actionId: string | null = null,
): EventEnvelope => ({
  type,
  roomId: 'room-ss',
  roomCode: 'SKETCH01',
  ts: '2026-10-22T10:00:30.000Z',
  seq: stream === 'ROOM' ? ++roomSeq : ++privateSeq,
  stream,
  actionId,
  payload,
})
const pub = (type: string, payload: unknown) => envelope('ROOM', type, payload)
const priv = (type: string, payload: unknown, actionId: string | null = null) =>
  envelope('PRIVATE', type, payload, actionId)
const apply = (e: EventEnvelope) => useRoomStore.getState().applyEnvelope(e)
const sketchy = () => useSketchySaurusStore.getState()

const snapshotDto = (game: GameSnapshot | null): SnapshotDto => ({
  room,
  serverNow: '2026-10-22T10:00:30.000Z',
  seq: { room: 10, private: 2 },
  game,
})

describe('roomStore × Sketchy Saurus', () => {
  beforeEach(() => {
    useRoomStore.getState().reset()
    roomSeq = 10
    privateSeq = 2
    useRoomStore
      .getState()
      .applySnapshot(snapshotDto(sketchySnapshot('SKETCH', ARTIST_ROLE) as unknown as GameSnapshot))
  })

  it('hydrates the Sketchy Saurus store from the room snapshot only', () => {
    expect(sketchy().role).toEqual(ARTIST_ROLE)
    expect(sketchy().submitted).toBe(false)
    expect(useGameSessionStore.getState().gameKey).toBe('sketchy-saurus')
    expect(useDinoImpostorStore.getState().strokes).toEqual([])
    expect(useDrawGuessStore.getState().strokes).toEqual([])
  })

  it('routes sketchy and shared impostor events correctly', () => {
    // SKETCHY_PROGRESS
    apply(pub('SKETCHY_PROGRESS', sketchyProgress))
    expect(sketchy().submittedGuestIds).toEqual(['ss-g1', 'ss-g3'])

    // Phase transition to GALLERY
    apply(pub('PHASE_STARTED', galleryStarted))
    expect(useGameSessionStore.getState().phase).toBe('GALLERY')

    // SKETCHY_GALLERY_OPENED
    apply(pub('SKETCHY_GALLERY_OPENED', { round: 1, drawings: sampleDrawings }))
    expect(sketchy().gallery).toHaveLength(5)

    // Shared IMPOSTOR_CHAT_MESSAGE
    apply(pub('IMPOSTOR_CHAT_MESSAGE', sampleChat[0]))
    expect(sketchy().chat).toHaveLength(1)
    expect(sketchy().chat[0].text).toBe(sampleChat[0].text)

    // Shared IMPOSTOR_VOTE_PROGRESS
    apply(pub('IMPOSTOR_VOTE_PROGRESS', { votedGuestIds: ['ss-g1', 'ss-g3'] }))
    expect(sketchy().votedGuestIds).toEqual(['ss-g1', 'ss-g3'])

    // Shared IMPOSTOR_VOTE_CLOSED
    apply(pub('IMPOSTOR_VOTE_CLOSED', voteClosedImpostorCaught))
    expect(sketchy().voteClosed?.caught).toBe(true)

    // Phase transition to GUESS
    apply(pub('PHASE_STARTED', guessStarted))
    expect(useGameSessionStore.getState().phase).toBe('GUESS')

    // Shared IMPOSTOR_GUESS_OPEN
    apply(priv('IMPOSTOR_GUESS_OPEN', guessOpenFixture))
    expect(sketchy().guessOpen?.category.label).toBe('Fossils')

    // Phase transition to ROUND_END
    apply(pub('PHASE_STARTED', roundEndStarted))
    apply(pub('IMPOSTOR_ROUND_ENDED', roundEndedCaughtWrongGuess))
    expect(sketchy().roundEnd?.outcome).toBe('CAUGHT')
    expect(sketchy().roundEnd?.decoyWord).toBe('Trilobite')
  })

  it('tracks ACTION_ACK on the sketchy store', () => {
    sketchy().trackCommand('act-sketch-1', { type: 'SKETCHY_SUBMIT' })
    expect(
      sketchy().applyCommandError(
        { code: 'ALREADY_SUBMITTED', message: 'Already submitted', retryable: false },
        'act-sketch-1',
      ),
    ).toBe('handled')
    // After ack, the tracker clears it
    apply(priv('ACTION_ACK', {}, 'act-sketch-1'))
    expect(
      sketchy().applyCommandError(
        { code: 'ALREADY_SUBMITTED', message: 'Already submitted', retryable: false },
        'act-sketch-1',
      ),
    ).toBe('unhandled')
  })

  it('resets sketchy state when game finishes or aborts', () => {
    apply(pub('GAME_ABORTED', { reason: 'HOST_QUIT' }))
    expect(sketchy().role).toBeNull()
    expect(sketchy().gallery).toBeNull()
  })
})
