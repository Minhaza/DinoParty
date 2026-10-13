/** DTO shapes mirrored from docs/api-contracts.md §2.1; keep in sync with that file. */

export type GameKey = 'draw-guess' | 'telephone'

export interface GuestDto {
  id: string
  nickname: string
  avatarKey: string
  avatarColor: string | null
  createdAt: string
}

export interface CreateGuestResponse {
  guest: GuestDto
  token: string
  expiresAt: string
}

export interface GuestProfileInput {
  nickname: string
  avatarKey: string
  avatarColor?: string
}

export type RoomStatus = 'WAITING' | 'STARTING' | 'PLAYING' | 'RESULTS' | 'CLOSED'

export type SettingsMap = Record<string, number | boolean | string>

export interface PlayerDto {
  guestId: string
  nickname: string
  avatarKey: string
  avatarColor: string | null
  ready: boolean
  connection: 'CONNECTED' | 'DISCONNECTED' | 'LEFT'
  isHost: boolean
  score: number | null
}

export interface RoomDto {
  id: string
  code: string
  inviteUrl: string
  status: RoomStatus
  gameKey: GameKey
  hostGuestId: string
  minPlayers: number
  maxPlayers: number
  settings: SettingsMap
  players: PlayerDto[]
  sessionId: string | null
  createdAt: string
}

export interface RoomPreviewDto {
  code: string
  gameKey: GameKey
  status: RoomStatus
  playerCount: number
  maxPlayers: number
  hostNickname: string
}

export interface CreateRoomInput {
  gameKey: GameKey
  maxPlayers?: number
  settings?: SettingsMap
}

export interface UpdateRoomSettingsInput {
  gameKey?: GameKey
  maxPlayers?: number
  settings?: SettingsMap
}

export interface SnapshotSeq {
  room: number
  private: number
}

export interface SnapshotDto {
  room: RoomDto
  serverNow: string
  seq: SnapshotSeq
  game: unknown | null
}

export type StreamType = 'ROOM' | 'PRIVATE'

export interface EventEnvelope<T = unknown> {
  type: string
  roomId: string | null
  roomCode: string | null
  ts: string
  seq: number
  stream: StreamType
  actionId: string | null
  payload: T
}

export interface ClientCommand<T = unknown> {
  type: string
  actionId?: string
  phaseId?: string | null
  payload?: T
}

export interface PlayerJoinedPayload {
  player: PlayerDto
}

export interface PlayerLeftPayload {
  guestId: string
  reason: 'LEFT' | 'KICKED' | 'TIMEOUT'
}

export interface ReadyUpdatedPayload {
  guestId: string
  ready: boolean
}

export interface HostChangedPayload {
  hostGuestId: string
}

export interface RoomSettingsUpdatedPayload {
  gameKey: GameKey
  settings: SettingsMap
  minPlayers: number
  maxPlayers: number
}

export interface PlayerDisconnectedPayload {
  guestId: string
  graceEndsAt: string
}

export interface PlayerReconnectedPayload {
  guestId: string
}

export interface RoomClosedPayload {
  reason: 'EMPTY' | 'HOST_CLOSED' | string
}

export interface RoomStatusChangedPayload {
  status: RoomStatus
  startsAt?: string
}

export interface ActionAckPayload {
  actionId: string
  accepted: boolean
}

export interface GameErrorPayload {
  actionId?: string | null
  code: string
  message: string
  retryable: boolean
}

export interface PongPayload {
  serverNow: string
}

