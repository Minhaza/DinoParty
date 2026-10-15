import type { TelephoneKind } from './types'

/** Player-facing copy per step kind, kept in one place so header, editors and tests agree. */
export const STEP_COPY: Record<TelephoneKind, { title: string; instruction: string }> = {
  PROMPT: {
    title: 'Write a prompt',
    instruction: 'Write a funny sentence for someone else to draw!',
  },
  DRAW: {
    title: 'Draw it',
    instruction: 'Draw this sentence as best you can!',
  },
  DESCRIBE: {
    title: 'Describe the drawing',
    instruction: 'What do you think this drawing shows?',
  },
}

/** "Step 3 of 6 — Describe the drawing" (steps are 0-based on the wire). */
export function stepLabel(step: number, totalSteps: number, kind: TelephoneKind): string {
  const total = totalSteps > 0 ? ` of ${totalSteps}` : ''
  return `Step ${step + 1}${total} — ${STEP_COPY[kind].title}`
}
