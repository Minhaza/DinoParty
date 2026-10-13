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
