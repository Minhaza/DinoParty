import { ApiError } from './client'

const MESSAGES: Record<string, string> = {
  ROOM_NOT_FOUND: 'No room with that code exists. Check the code and try again.',
  ROOM_FULL: 'That room is full.',
  ROOM_IN_PROGRESS: 'That room has already started playing.',
  ROOM_CLOSED: 'That room has been closed.',
  ALREADY_IN_ROOM: 'You are already in a room. Leave it before joining or creating another.',
  NOT_HOST: 'Only the host can change room settings.',
  INVALID_PHASE: 'This cannot be changed right now.',
  GAME_NOT_FOUND: 'That game is not available.',
  RATE_LIMITED: 'Too many attempts. Please wait a moment and try again.',
  UNAUTHENTICATED: 'Your session has expired. Please set up your profile again.',
  VALIDATION_FAILED: 'Some of the values are not valid.',
  WORD_POOL_TOO_SMALL: 'Not enough words in the selection. Choose more categories or word packs.',
  WORD_PACK_NOT_FOUND: 'That word pack could not be found.',
  WORD_PACK_LIMIT_REACHED: 'Word pack limit reached.',
  IMPORT_STALE: 'The word pack was modified or the file changed. Please preview again.',
  PAYLOAD_TOO_LARGE: 'The file exceeds the 1 MiB size limit.',
  UNSUPPORTED_MEDIA_TYPE: 'Only TXT and CSV files are supported.',
}

/** Turns any thrown value into a short, user-facing sentence (never a stack or raw payload). */
export function describeError(error: unknown): string {
  if (error instanceof ApiError) {
    const code = error.code
    if (code && MESSAGES[code]) return MESSAGES[code]
    if (error.status >= 500) return 'The server had a problem. Please try again.'
    return 'Something went wrong. Please try again.'
  }
  return 'Could not reach the server. Check your connection and try again.'
}

/** Returns the server's message for one invalid field, if any. */
export function fieldError(error: unknown, field: string): string | null {
  if (!(error instanceof ApiError)) return null
  return error.problem?.fieldErrors?.find((f) => f.field === field)?.message ?? null
}
