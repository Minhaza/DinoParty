/** DTO shapes mirrored from docs/api-contracts.md §2.1; keep in sync with that file. */

/**
 * Game keys come from the server's game registry (`GET /games`), so any string is possible; a key
 * this client has no module for is still displayable (features/games/gameModules.tsx).
 */
export type GameKey = string

/** Games this client can render. Only for branches into game-specific code, never for metadata. */
export type KnownGameKey = 'draw-guess' | 'telephone'

/** §2.6: members that do not apply to a field's `type` are omitted by the server. */
export interface IntSettingField {
  type: 'int'
  key: string
  label: string
  default: number
  min: number
  max: number
  /** Also accepted besides `min…max`; means "automatic". */
  autoValue?: number
}

export interface BoolSettingField {
  type: 'bool'
  key: string
  label: string
  default: boolean
  /** Present only when narrower than `[false, true]`, e.g. `[true]`. */
  options?: boolean[]
}

export interface EnumSettingField {
  type: 'enum'
  key: string
  label: string
  default: string
  options: string[]
}

export interface ListSettingField {
  type: 'list'
  key: string
  label: string
  default: string[]
  maxItems: number
  itemFormat: 'category-key' | 'uuid' | string
  exclusiveOption?: string
}

export type SettingField = IntSettingField | BoolSettingField | EnumSettingField | ListSettingField

export interface GameSummaryDto {
  key: GameKey
  name: string
  tagline: string
  minPlayers: number
  maxPlayers: number
  iconKey: string
  enabled: boolean
}

export interface GameDetailDto extends GameSummaryDto {
  description: string
  howToPlay: string[]
  settingsSchema: SettingField[]
  defaultSettings: SettingsMap
}

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

export type SettingsValue = number | boolean | string | string[]

export type SettingsMap = Record<string, SettingsValue>

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

export interface LastSessionDto {
  sessionId: string
  status: 'FINISHED' | 'ABORTED' | 'INTERRUPTED'
  endReason: string
  finishedAt: string
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
  lastSession?: LastSessionDto | null
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

export type RosterPlayerStatus = 'ACTIVE' | 'DISCONNECTED' | 'LEFT'

export interface SessionRosterPlayer {
  guestId: string
  status: RosterPlayerStatus
}

export interface StandingsEntry {
  rank: number
  guestId: string
  nickname?: string
  score: number
  detail?: Record<string, unknown>
}

export interface RoundResultDto {
  roundNo: number
  startedAt: string
  endedAt: string
  endReason: string
  scoreDeltas: Record<string, number>
  meta?: Record<string, unknown>
}

export interface ResultsDto {
  sessionId: string
  gameKey: GameKey | string
  status: 'FINISHED' | 'ABORTED' | 'INTERRUPTED'
  endReason: string
  startedAt: string
  finishedAt: string
  standings: StandingsEntry[]
  rounds: RoundResultDto[]
}

export interface GameSnapshotHeader {
  sessionId: string
  gameKey: GameKey | string
  status: 'RUNNING' | 'FINISHED'
  order: string[]
  phase: string
  phaseId: string
  phaseStartedAt: string
  deadlineAt: string | null
  scores: Record<string, number> | null
  players: SessionRosterPlayer[]
  results: { standings: StandingsEntry[] } | null
  rematch: { votes: Record<string, boolean> } | null
}

export type GameSnapshot = GameSnapshotHeader & Record<string, unknown>

export interface SnapshotSeq {
  room: number
  private: number
}

export interface SnapshotDto {
  room: RoomDto
  serverNow: string
  seq: SnapshotSeq
  game: GameSnapshot | null
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
  reason?: string
  startsAt?: string
}

export interface GameStartedPayload {
  sessionId: string
  gameKey: GameKey | string
  settings: SettingsMap
  order: string[]
}

export interface PhaseStartedPayload {
  phase: string
  phaseId: string
  round?: number
  deadlineAt: string | null
  [key: string]: unknown
}

export interface GameFinishedPayload {
  sessionId: string
  endReason: 'COMPLETED' | 'INSUFFICIENT_PLAYERS' | string
  standings: StandingsEntry[]
  resultsReady: boolean
}

export interface GameAbortedPayload {
  sessionId: string
  reason:
    | 'NO_COMPLETED_ROUND'
    | 'INTERNAL_ERROR'
    | 'SERVER_RESTART'
    | 'SERVER_SHUTDOWN'
    | 'ROOM_CLOSED'
    | string
}

export interface RematchVotesUpdatedPayload {
  votes: Record<string, boolean>
}

export interface RematchVoteInput {
  vote: boolean
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

export type WordDifficulty = 'EASY' | 'MEDIUM' | 'HARD'
export type DifficultySetting = 'easy' | 'medium' | 'hard' | 'mixed'
export type WordSourceSetting = 'built-in' | 'custom' | 'combined'

export interface WordCatalogOption {
  key: string
  label: string
}

export interface WordCatalogPreset {
  key: string
  label: string
  includes: string[]
}

export interface WordCategoriesDto {
  locale: string
  categories: WordCatalogOption[]
  presets: WordCatalogPreset[]
}

export interface WordDifficultiesDto {
  levels: WordCatalogOption[]
  presets: WordCatalogPreset[]
}

export interface CategoryCountDto {
  key: string
  total: number
  byDifficulty: Record<WordDifficulty, number>
}

export interface WordCountsDto {
  locale: string
  category: string
  difficulty: 'EASY' | 'MEDIUM' | 'HARD' | 'MIXED'
  total: number
  byDifficulty: Record<WordDifficulty, number>
  byCategory: CategoryCountDto[]
}

export interface WordPackDto {
  id: string
  roomId: string
  name: string
  description: string
  wordCount: number
  createdAt: string
  updatedAt: string
}

export interface WordPackWordDto {
  id: string
  text: string
  category: string
  difficulty: WordDifficulty
}

export interface WordPackWordsDto {
  words: WordPackWordDto[]
  offset: number
  limit: number
  total: number
}

export interface WordEntryInput {
  text: string
  category?: string
  difficulty?: string
}

export interface AddWordsResultDto {
  added: number
  skipped: number
  pack: WordPackDto
}

export type ImportFormat = 'TXT' | 'CSV'

export type RowProblem =
  | 'BLANK_WORD'
  | 'TOO_LONG'
  | 'INVALID_CHARACTER'
  | 'INVALID_DIFFICULTY'
  | 'INVALID_CATEGORY'
  | 'MALFORMED_ROW'

export interface RowError {
  line: number
  code: RowProblem
  message: string
}

export interface DuplicateRow {
  line: number
  of: 'FILE' | 'PACK'
}

export interface PreviewRow {
  line: number
  text: string
  category: string
  difficulty: WordDifficulty
}

export interface ImportPreviewDto {
  format: ImportFormat
  sha256: string
  packVersion: number
  totalRows: number
  validCount: number
  duplicateCount: number
  invalidCount: number
  remainingCapacity: number
  exceedsPackLimit: boolean
  preview: PreviewRow[]
  duplicates: DuplicateRow[]
  errors: RowError[]
  truncated: boolean
}

export interface ImportResultDto {
  imported: number
  skipped: number
  duplicates: number
  invalid: number
  pack: WordPackDto
}

export interface CreateWordPackInput {
  name: string
  description?: string
}

export interface UpdateWordPackInput {
  name?: string
  description?: string
}
