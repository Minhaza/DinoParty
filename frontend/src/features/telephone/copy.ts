import type { TelephoneKind } from './types'

/** Player-facing copy per step kind, kept in one place so header, editors and tests agree. */
export const STEP_COPY: Record<TelephoneKind, { title: string }> = {
  PROMPT: { title: 'Write a prompt' },
  DRAW: { title: 'Draw it' },
  DESCRIBE: { title: 'Describe the drawing' },
}

/** "Step 3 of 6 — Describe the drawing" (steps are 0-based on the wire). */
export function stepLabel(step: number, totalSteps: number, kind: TelephoneKind): string {
  const total = totalSteps > 0 ? ` of ${totalSteps}` : ''
  return `Step ${step + 1}${total} — ${STEP_COPY[kind].title}`
}

/** "Step 3 of 6" — the visible header label; the full step title goes to the live region. */
export function stepCounter(step: number, totalSteps: number): string {
  const total = totalSteps > 0 ? ` of ${totalSteps}` : ''
  return `Step ${step + 1}${total}`
}

/** A pending submission that was not confirmed by the reconnect snapshot. */
export const SUBMIT_NOT_RECEIVED = 'Your entry was not received. Submit it again.'

/** A pending submission that got neither an ACK nor an error in time. */
export const SUBMIT_NO_ANSWER =
  'No answer from the server yet. Check your connection and try again.'

/**
 * Player-facing copy for a rejected submission (api-contracts §1, §3.10). The server message is
 * never shown: it is not written for players and must not become a channel for game details.
 */
export function submitErrorCopy(code: string, retryable: boolean): string {
  switch (code) {
    case 'VALIDATION_FAILED':
      return 'That entry was not accepted. Check it and try again.'
    case 'PAYLOAD_TOO_LARGE':
      return 'That entry is too large to send.'
    case 'NOT_IN_ROOM':
      return 'You are no longer in this game.'
    case 'RATE_LIMITED':
      return 'Too fast! Wait a moment and try again.'
    default:
      return retryable ? 'Too fast! Wait a moment and try again.' : 'Your entry could not be sent.'
  }
}
