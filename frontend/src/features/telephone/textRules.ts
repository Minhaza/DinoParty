// Prompt and description text rules ([TP-14]); mirrors the server's `TelephoneText` so the counter
// and the submit button agree with what the server will accept.

export const TELEPHONE_TEXT_MAX = 140

// Control characters (newlines included) and invisible format characters (bidi overrides,
// zero-width marks) are stripped by the server, so they never count.
const CONTROL = /[\p{Cc}\p{Cf}]/gu

/** The text as the server stores it: control/format characters stripped, then trimmed. */
export function sanitizeTelephoneText(raw: string): string {
  return raw.replace(CONTROL, '').trim()
}

/** Length in Unicode code points (not UTF-16 units), the unit of the 1–140 rule. */
export function codePointLength(text: string): number {
  return Array.from(text).length
}

export type TextValidation = 'EMPTY' | 'TOO_LONG' | null

export function validateTelephoneText(raw: string): TextValidation {
  const length = codePointLength(sanitizeTelephoneText(raw))
  if (length === 0) return 'EMPTY'
  if (length > TELEPHONE_TEXT_MAX) return 'TOO_LONG'
  return null
}
