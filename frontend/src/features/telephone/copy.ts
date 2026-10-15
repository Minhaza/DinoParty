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
