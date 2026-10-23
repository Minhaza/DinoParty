import * as React from 'react'

import type { DrawStrokePayload } from '@/features/drawing/types'
import { useRealtimeClient } from '@/features/realtime/realtimeContext'
import { generateUUID } from '@/lib/uuid'
import { useGameSessionStore } from '@/stores/gameSessionStore'

import type { TrackedImpostorCommand } from '../common/impostorState'
import {
  sanitizeChatText,
  sanitizeGuessText,
  validateChatText,
  validateGuessText,
} from '../common/textRules'
import { IMPOSTOR_COMMANDS } from '../common/types'
import { SKETCHY_COMMANDS } from './types'

export type TrackSketchyCommand = (actionId: string, command: TrackedImpostorCommand) => void

export interface SketchyCommands {
  drawStroke: (payload: DrawStrokePayload) => boolean
  undo: () => boolean
  clear: () => boolean
  submit: () => boolean
  chat: (text: string) => boolean
  vote: (targetGuestId: string | null) => boolean
  guess: (text: string) => boolean
}

/**
 * Sends the Sketchy Saurus commands for the current phase (api-contracts §3.12).
 * Every sender returns false instead of throwing if the socket is down or no phase is active.
 */
export function useSketchyCommands(trackCommand: TrackSketchyCommand): SketchyCommands {
  const client = useRealtimeClient()
  const phaseId = useGameSessionStore((s) => s.phaseId)

  const send = React.useCallback(
    (type: string, payload: unknown, tracked: TrackedImpostorCommand): boolean => {
      if (!phaseId) return false
      const actionId = generateUUID()
      try {
        client.sendCommand({ type, payload, actionId, phaseId })
      } catch {
        return false
      }
      trackCommand(actionId, { ...tracked, phaseId })
      return true
    },
    [client, phaseId, trackCommand],
  )

  return React.useMemo(
    () => ({
      drawStroke: (payload) =>
        send(SKETCHY_COMMANDS.drawStroke, payload, {
          type: SKETCHY_COMMANDS.drawStroke,
          strokeId: payload.strokeId,
        }),
      undo: () => send(SKETCHY_COMMANDS.undo, {}, { type: SKETCHY_COMMANDS.undo }),
      clear: () => send(SKETCHY_COMMANDS.clear, {}, { type: SKETCHY_COMMANDS.clear }),
      submit: () => send(SKETCHY_COMMANDS.submit, {}, { type: SKETCHY_COMMANDS.submit }),
      chat: (raw) => {
        if (validateChatText(raw) !== null) return false
        return send(
          IMPOSTOR_COMMANDS.chat,
          { text: sanitizeChatText(raw) },
          { type: IMPOSTOR_COMMANDS.chat },
        )
      },
      vote: (targetGuestId) =>
        send(
          IMPOSTOR_COMMANDS.vote,
          { targetGuestId },
          { type: IMPOSTOR_COMMANDS.vote, target: targetGuestId },
        ),
      guess: (raw) => {
        if (validateGuessText(raw) !== null) return false
        return send(
          IMPOSTOR_COMMANDS.guess,
          { text: sanitizeGuessText(raw) },
          { type: IMPOSTOR_COMMANDS.guess },
        )
      },
    }),
    [send],
  )
}
