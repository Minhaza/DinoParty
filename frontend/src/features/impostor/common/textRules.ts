// Chat and guess text rules ([DI-8], [DI-11]); mirror the server so the counter and the send button
// agree with what it will accept.

import { codePointLength, sanitizeTelephoneText } from '@/features/telephone/textRules'

import { IMPOSTOR_CHAT_MAX, IMPOSTOR_GUESS_MAX } from './types'

export { codePointLength }

/** Chat text as the server stores it: control/format characters stripped, then trimmed. */
export const sanitizeChatText = sanitizeTelephoneText

/** Guess text as the server reads it: trimmed (the matcher normalizes the rest). */
export function sanitizeGuessText(raw: string): string {
  return raw.trim()
}

export type TextValidation = 'EMPTY' | 'TOO_LONG' | null

function validate(text: string, max: number): TextValidation {
  const length = codePointLength(text)
  if (length === 0) return 'EMPTY'
  if (length > max) return 'TOO_LONG'
  return null
}

export function validateChatText(raw: string): TextValidation {
  return validate(sanitizeChatText(raw), IMPOSTOR_CHAT_MAX)
}

export function validateGuessText(raw: string): TextValidation {
  return validate(sanitizeGuessText(raw), IMPOSTOR_GUESS_MAX)
}
