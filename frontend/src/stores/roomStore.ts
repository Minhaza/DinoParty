import { create } from 'zustand'

import type {
  EventEnvelope,
  GameAbortedPayload,
  GameErrorPayload,
  GameFinishedPayload,
  GameStartedPayload,
  HostChangedPayload,
  PlayerDisconnectedPayload,
  PlayerJoinedPayload,
  PlayerLeftPayload,
  PlayerReconnectedPayload,
  ReadyUpdatedPayload,
  RematchVotesUpdatedPayload,
  RoomDto,
  RoomSettingsUpdatedPayload,
  RoomStatusChangedPayload,
  SnapshotDto,
  SnapshotSeq,
} from '@/api/types'
import { updateServerClockOffset } from '@/lib/serverClock'
import type {
  DrawGuessPhaseStartedPayload,
  DrawGuessSnapshot,
  DrawingUpdatedPayload,
  DrawerWordPayload,
  GuessAcceptedPayload,
  GuessChatPayload,
  GuesserChatPayload,
  GuessFeedbackPayload,
  HintUpdatedPayload,
  RoundEndedPayload,
  WordChoicesPayload,
} from '@/features/drawguess/types'
import { useDrawGuessStore } from '@/features/drawguess/drawGuessStore'
import type {
  DinoImpostorPhaseStartedPayload,
  DinoImpostorSnapshot,
  ImpostorChatMessagePayload,
  ImpostorDrawingUpdatedPayload,
  ImpostorGuessOpenPayload,
  ImpostorRolePayload,
  ImpostorRoundEndedPayload,
  ImpostorVoteClosedPayload,
  ImpostorVoteProgressPayload,
} from '@/features/impostor/common/types'
import { useDinoImpostorStore } from '@/features/impostor/dino/dinoImpostorStore'
import { useSketchySaurusStore } from '@/features/impostor/sketchy/sketchySaurusStore'
import type {
  SketchyGalleryOpenedPayload,
  SketchyProgressPayload,
  SketchySaurusPhaseStartedPayload,
  SketchySaurusSnapshot,
} from '@/features/impostor/sketchy/types'
import { useRevealStore } from '@/features/telephone/revealStore'
import { useTelephoneStore, type CommandErrorOutcome } from '@/features/telephone/telephoneStore'
import type {
  AlbumRevealedPayload,
  TelephonePhaseStartedPayload,
  TelephoneProgressPayload,
  TelephoneSnapshot,
  TelephoneStepEndedPayload,
  TelephoneTaskPayload,
} from '@/features/telephone/types'

import { useGameSessionStore } from './gameSessionStore'

export type SyncStatus = 'idle' | 'syncing' | 'synced' | 'error'

export interface RoomState {
  room: RoomDto | null
  serverNow: string | null
  lastSeq: SnapshotSeq
  syncStatus: SyncStatus
  needsResync: boolean
  lastError: string | null
  terminalNotice: string | null

  applySnapshot: (snapshot: SnapshotDto) => void
  applyEnvelope: (envelope: EventEnvelope<unknown>) => 'applied' | 'ignored' | 'gap'
  setSyncStatus: (status: SyncStatus) => void
  setNeedsResync: (needsResync: boolean) => void
  clearLastError: () => void
  /** Shows `room` before its snapshot; entering a different room first drops all state of the last. */
  enterRoom: (room: RoomDto) => void
  reset: () => void
}

const initialSeq: SnapshotSeq = { room: 0, private: 0 }

export const useRoomStore = create<RoomState>((set, get) => ({
  room: null,
  serverNow: null,
  lastSeq: { ...initialSeq },
  syncStatus: 'idle',
  needsResync: false,
  lastError: null,
  terminalNotice: null,

  applySnapshot: (snapshot: SnapshotDto) => {
    updateServerClockOffset(snapshot.serverNow)
    useGameSessionStore.getState().applySnapshot(snapshot.game, snapshot.room)

    if (snapshot.room.gameKey === 'draw-guess' || snapshot.game?.gameKey === 'draw-guess') {
      useDrawGuessStore.getState().applySnapshot(snapshot.game as unknown as DrawGuessSnapshot)
    }
    if (snapshot.room.gameKey === 'telephone' || snapshot.game?.gameKey === 'telephone') {
      useTelephoneStore.getState().applySnapshot(snapshot.game as unknown as TelephoneSnapshot)
      useRevealStore.getState().applySnapshot(snapshot.game as unknown as TelephoneSnapshot)
    }
    if (snapshot.room.gameKey === 'dino-impostor' || snapshot.game?.gameKey === 'dino-impostor') {
      useDinoImpostorStore
        .getState()
        .applySnapshot(snapshot.game as unknown as DinoImpostorSnapshot | null)
    }
    if (snapshot.room.gameKey === 'sketchy-saurus' || snapshot.game?.gameKey === 'sketchy-saurus') {
      useSketchySaurusStore
        .getState()
        .applySnapshot(snapshot.game as unknown as SketchySaurusSnapshot | null)
    }

    set({
      room: snapshot.room,
      serverNow: snapshot.serverNow,
      lastSeq: { room: snapshot.seq.room, private: snapshot.seq.private },
      syncStatus: 'synced',
      needsResync: false,
    })
  },

  applyEnvelope: (envelope: EventEnvelope<unknown>) => {
    const state = get()

    if (envelope.ts) {
      updateServerClockOffset(envelope.ts)
    }

    // Handle errors arriving on /user/queue/errors (seq = 0, unsequenced)
    if (envelope.type === 'ERROR' || envelope.seq === 0) {
      const payload = envelope.payload as GameErrorPayload | undefined
      if (payload && typeof payload.message === 'string') {
        const outcome = routeCommandError(payload, envelope.actionId)
        if (outcome === 'resync') set({ needsResync: true })
        else if (outcome === 'unhandled') set({ lastError: payload.message })
      }
      return 'applied'
    }

    // A snapshot replaces the remembered seq wholesale (contract §3.2)
    if (envelope.type === 'ROOM_SNAPSHOT') {
      state.applySnapshot(envelope.payload as SnapshotDto)
      return 'applied'
    }

    const streamKey = envelope.stream === 'ROOM' ? 'room' : 'private'
    const currentSeq = state.lastSeq[streamKey]

    // Deduplication check: ignore events at or before current sequence
    if (envelope.seq <= currentSeq) {
      return 'ignored'
    }

    // Gap detection: if there is a sequence gap, trigger resynchronization
    if (envelope.seq > currentSeq + 1) {
      set({ needsResync: true })
      return 'gap'
    }

    // Next event in sequence: advance sequence counter
    const nextSeq: SnapshotSeq = {
      ...state.lastSeq,
      [streamKey]: envelope.seq,
    }

    // Forward game lifecycle events to gameSessionStore
    const resyncRequested = dispatchGameLifecycleEvents(envelope)

    // Dispatch event to update room state
    const currentRoom = state.room
    if (!currentRoom) {
      // Ignore until a snapshot arrives
      set(resyncRequested ? { lastSeq: nextSeq, needsResync: true } : { lastSeq: nextSeq })
      return 'applied'
    }

    const updatedRoom = reduceRoomEvent(currentRoom, envelope)

    let terminalNotice = state.terminalNotice
    let lastError = state.lastError
    let needsResync = state.needsResync || resyncRequested

    if (envelope.type === 'SESSION_REPLACED') {
      terminalNotice = 'SESSION_REPLACED'
    } else if (envelope.type === 'GAME_ERROR') {
      const err = envelope.payload as GameErrorPayload
      const outcome = routeCommandError(err, envelope.actionId)
      if (outcome === 'resync') needsResync = true
      else if (outcome === 'unhandled') lastError = err.message || err.code
    }

    set({
      room: updatedRoom,
      lastSeq: nextSeq,
      serverNow: envelope.ts || state.serverNow,
      terminalNotice,
      lastError,
      needsResync,
    })

    return 'applied'
  },

  setSyncStatus: (syncStatus) => set({ syncStatus }),
  setNeedsResync: (needsResync) => set({ needsResync }),
  clearLastError: () => set({ lastError: null }),

  enterRoom: (room) => {
    const current = get().room
    if (current?.code === room.code) return
    // Drafts, tasks and stream positions belong to one room (and the guest in it); never carry them over.
    if (current) get().reset()
    set({ room })
  },

  reset: () => {
    useGameSessionStore.getState().reset()
    useDrawGuessStore.getState().reset()
    useTelephoneStore.getState().reset()
    useRevealStore.getState().reset()
    useDinoImpostorStore.getState().reset()
    useSketchySaurusStore.getState().reset()
    set({
      room: null,
      serverNow: null,
      lastSeq: { ...initialSeq },
      syncStatus: 'idle',
      needsResync: false,
      lastError: null,
      terminalNotice: null,
    })
  },
}))

/** Each game store only claims the actionIds it sent; the first one that knows the command wins. */
function routeCommandError(
  payload: GameErrorPayload,
  actionId: string | null | undefined,
): CommandErrorOutcome {
  const reveal = useRevealStore.getState().applyCommandError(payload, actionId)
  if (reveal !== 'unhandled') return reveal
  const outcome = useTelephoneStore.getState().applyCommandError(payload, actionId)
  if (outcome !== 'unhandled') return outcome
  const impostor = useDinoImpostorStore.getState().applyCommandError(payload, actionId)
  if (impostor !== 'unhandled') return impostor
  const sketchy = useSketchySaurusStore.getState().applyCommandError(payload, actionId)
  if (sketchy !== 'unhandled') return sketchy
  return useDrawGuessStore.getState().applyCommandError(payload, actionId)
}

/** Applies an event to the session and game stores; returns `true` when a snapshot is needed. */
function dispatchGameLifecycleEvents(envelope: EventEnvelope<unknown>): boolean {
  const sessionStore = useGameSessionStore.getState()
  const drawGuessStore = useDrawGuessStore.getState()
  const telephoneStore = useTelephoneStore.getState()
  const revealStore = useRevealStore.getState()
  const dinoImpostorStore = useDinoImpostorStore.getState()
  const sketchyStore = useSketchySaurusStore.getState()
  const gameKey = sessionStore.gameKey ?? useRoomStore.getState().room?.gameKey
  const activeImpostorStore = gameKey === 'sketchy-saurus' ? sketchyStore : dinoImpostorStore
  let resync = false

  switch (envelope.type) {
    case 'GAME_STARTED':
      sessionStore.applyGameStarted(envelope.payload as GameStartedPayload)
      drawGuessStore.reset()
      telephoneStore.reset()
      revealStore.reset()
      dinoImpostorStore.reset()
      sketchyStore.reset()
      break
    case 'PHASE_STARTED': {
      const payload = envelope.payload as DrawGuessPhaseStartedPayload
      sessionStore.applyPhaseStarted(payload, envelope.ts)
      if (gameKey === 'dino-impostor') {
        resync = dinoImpostorStore.applyPhaseStarted(
          envelope.payload as DinoImpostorPhaseStartedPayload,
        )
      } else if (gameKey === 'sketchy-saurus') {
        resync = sketchyStore.applyPhaseStarted(
          envelope.payload as SketchySaurusPhaseStartedPayload,
        )
      } else if (gameKey === 'telephone') {
        const telephonePhase = envelope.payload as TelephonePhaseStartedPayload
        telephoneStore.applyPhaseStarted(telephonePhase)
        revealStore.applyPhaseStarted(telephonePhase, useGameSessionStore.getState().sessionId)
      } else {
        drawGuessStore.applyPhaseStarted(payload)
      }
      break
    }
    case 'TELEPHONE_TASK':
      telephoneStore.applyTask(envelope.payload as TelephoneTaskPayload)
      break
    case 'TELEPHONE_PROGRESS':
      telephoneStore.applyProgress(envelope.payload as TelephoneProgressPayload)
      break
    case 'TELEPHONE_STEP_ENDED':
      telephoneStore.applyStepEnded(envelope.payload as TelephoneStepEndedPayload)
      break
    case 'ALBUM_REVEALED':
      revealStore.applyAlbumRevealed(envelope.payload as AlbumRevealedPayload)
      break
    case 'DRAWING_UPDATED':
      drawGuessStore.applyDrawingUpdated(envelope.payload as DrawingUpdatedPayload)
      break
    case 'HINT_UPDATED':
      drawGuessStore.applyHintUpdated(envelope.payload as HintUpdatedPayload)
      break
    case 'GUESS_ACCEPTED': {
      const payload = envelope.payload as GuessAcceptedPayload
      drawGuessStore.applyGuessAccepted(payload)
      sessionStore.updateScore(payload.guestId, payload.totalScore)
      break
    }
    case 'GUESS_CHAT':
      drawGuessStore.applyGuessChat(envelope.payload as GuessChatPayload, envelope.ts)
      break
    case 'GUESSER_CHAT':
      drawGuessStore.applyGuesserChat(envelope.payload as GuesserChatPayload, envelope.ts)
      break
    case 'IMPOSTOR_ROLE':
      activeImpostorStore.applyRole(envelope.payload as ImpostorRolePayload)
      break
    case 'IMPOSTOR_DRAWING_UPDATED':
      dinoImpostorStore.applyDrawingUpdated(envelope.payload as ImpostorDrawingUpdatedPayload)
      break
    case 'SKETCHY_PROGRESS':
      sketchyStore.applyProgress(envelope.payload as SketchyProgressPayload)
      break
    case 'SKETCHY_GALLERY_OPENED':
      sketchyStore.applyGalleryOpened(envelope.payload as SketchyGalleryOpenedPayload)
      break
    case 'IMPOSTOR_CHAT_MESSAGE':
      activeImpostorStore.applyChatMessage(envelope.payload as ImpostorChatMessagePayload)
      break
    case 'IMPOSTOR_VOTE_PROGRESS':
      activeImpostorStore.applyVoteProgress(envelope.payload as ImpostorVoteProgressPayload)
      break
    case 'IMPOSTOR_VOTE_CLOSED':
      activeImpostorStore.applyVoteClosed(envelope.payload as ImpostorVoteClosedPayload)
      break
    case 'IMPOSTOR_GUESS_OPEN':
      activeImpostorStore.applyGuessOpen(envelope.payload as ImpostorGuessOpenPayload)
      break
    case 'IMPOSTOR_ROUND_ENDED': {
      const payload = envelope.payload as ImpostorRoundEndedPayload
      activeImpostorStore.applyRoundEnded(payload)
      if (payload.scores) sessionStore.updateScores(payload.scores)
      break
    }
    case 'ACTION_ACK':
      drawGuessStore.applyActionAck(envelope.actionId)
      telephoneStore.applyActionAck(envelope.actionId)
      revealStore.applyActionAck(envelope.actionId)
      dinoImpostorStore.applyActionAck(envelope.actionId)
      sketchyStore.applyActionAck(envelope.actionId)
      break
    case 'ROUND_ENDED': {
      const payload = envelope.payload as RoundEndedPayload
      drawGuessStore.applyRoundEnded(payload)
      if (payload.scores) {
        sessionStore.updateScores(payload.scores)
      }
      break
    }
    case 'WORD_CHOICES':
      drawGuessStore.applyWordChoices(envelope.payload as WordChoicesPayload)
      break
    case 'DRAWER_WORD':
      drawGuessStore.applyDrawerWord(envelope.payload as DrawerWordPayload)
      break
    case 'GUESS_FEEDBACK':
      drawGuessStore.applyGuessFeedback(envelope.payload as GuessFeedbackPayload)
      break
    case 'GAME_FINISHED':
      sessionStore.applyGameFinished(envelope.payload as GameFinishedPayload)
      // Results never show a step's task or draft ([TP-8]); a reconnect snapshot carries none either.
      // The albums come from the album endpoints from now on, so the live copy is dropped.
      telephoneStore.reset()
      revealStore.reset()
      dinoImpostorStore.reset()
      sketchyStore.reset()
      break
    case 'GAME_ABORTED':
      sessionStore.applyGameAborted(envelope.payload as GameAbortedPayload)
      drawGuessStore.reset()
      telephoneStore.reset()
      revealStore.reset()
      dinoImpostorStore.reset()
      sketchyStore.reset()
      break
    case 'REMATCH_VOTES_UPDATED':
      sessionStore.applyRematchVotesUpdated(envelope.payload as RematchVotesUpdatedPayload)
      break
    case 'ROOM_STATUS_CHANGED': {
      const payload = envelope.payload as RoomStatusChangedPayload
      sessionStore.applyRoomStatusChanged(payload)
      if (payload.status === 'WAITING') {
        drawGuessStore.reset()
        telephoneStore.reset()
        revealStore.reset()
        dinoImpostorStore.reset()
        sketchyStore.reset()
      }
      break
    }
    case 'PLAYER_LEFT': {
      const { guestId } = envelope.payload as PlayerLeftPayload
      sessionStore.applyPlayerStatusChanged(guestId, 'LEFT')
      break
    }
    case 'PLAYER_DISCONNECTED': {
      const { guestId } = envelope.payload as PlayerDisconnectedPayload
      sessionStore.applyPlayerStatusChanged(guestId, 'DISCONNECTED')
      break
    }
    case 'PLAYER_RECONNECTED': {
      const { guestId } = envelope.payload as PlayerReconnectedPayload
      sessionStore.applyPlayerStatusChanged(guestId, 'ACTIVE')
      break
    }
    default:
      break
  }
  return resync
}

function reduceRoomEvent(room: RoomDto, envelope: EventEnvelope<unknown>): RoomDto {
  switch (envelope.type) {
    case 'PLAYER_JOINED': {
      const { player } = envelope.payload as PlayerJoinedPayload
      const exists = room.players.some((p) => p.guestId === player.guestId)
      const players = exists
        ? room.players.map((p) => (p.guestId === player.guestId ? player : p))
        : [...room.players, player]
      return { ...room, players }
    }

    case 'PLAYER_LEFT': {
      const { guestId } = envelope.payload as PlayerLeftPayload
      const players = room.players.filter((p) => p.guestId !== guestId)
      return { ...room, players }
    }

    case 'READY_UPDATED': {
      const { guestId, ready } = envelope.payload as ReadyUpdatedPayload
      const players = room.players.map((p) => (p.guestId === guestId ? { ...p, ready } : p))
      return { ...room, players }
    }

    case 'HOST_CHANGED': {
      const { hostGuestId } = envelope.payload as HostChangedPayload
      const players = room.players.map((p) => ({
        ...p,
        isHost: p.guestId === hostGuestId,
      }))
      return { ...room, hostGuestId, players }
    }

    case 'ROOM_SETTINGS_UPDATED': {
      const { gameKey, settings, minPlayers, maxPlayers } =
        envelope.payload as RoomSettingsUpdatedPayload
      return {
        ...room,
        gameKey,
        settings,
        minPlayers,
        maxPlayers,
      }
    }

    case 'PLAYER_DISCONNECTED': {
      const { guestId } = envelope.payload as PlayerDisconnectedPayload
      const players = room.players.map((p) =>
        p.guestId === guestId ? { ...p, connection: 'DISCONNECTED' as const } : p,
      )
      return { ...room, players }
    }

    case 'PLAYER_RECONNECTED': {
      const { guestId } = envelope.payload as PlayerReconnectedPayload
      const players = room.players.map((p) =>
        p.guestId === guestId ? { ...p, connection: 'CONNECTED' as const } : p,
      )
      return { ...room, players }
    }

    case 'ROOM_CLOSED': {
      return { ...room, status: 'CLOSED' }
    }

    case 'ROOM_STATUS_CHANGED': {
      const { status, reason } = envelope.payload as RoomStatusChangedPayload
      const resetReasons = [
        'REMATCH',
        'RESULTS_TIMEOUT',
        'GAME_ABORTED',
        'SERVER_RESTART',
        'START_CANCELLED',
      ]
      const shouldReset = reason && resetReasons.includes(reason)
      const players = shouldReset
        ? room.players.map((p) => ({ ...p, ready: false, score: null }))
        : room.players
      const sessionId = shouldReset && status === 'WAITING' ? null : room.sessionId
      return { ...room, status, sessionId, players }
    }

    case 'GAME_STARTED': {
      const { sessionId } = envelope.payload as GameStartedPayload
      return { ...room, status: 'PLAYING', sessionId }
    }

    case 'GAME_FINISHED': {
      return { ...room, status: 'RESULTS' }
    }

    case 'GAME_ABORTED': {
      return { ...room, status: 'WAITING', sessionId: null }
    }

    case 'ROOM_SNAPSHOT': {
      const snapshot = envelope.payload as SnapshotDto
      return snapshot.room
    }

    default:
      return room
  }
}
