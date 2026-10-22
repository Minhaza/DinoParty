import * as React from 'react'

import { generateUUID } from '@/lib/uuid'
import { useRealtimeClient } from '@/features/realtime/realtimeContext'
import { useGameSessionStore } from '@/stores/gameSessionStore'

import type { TrackedImpostorCommand } from './impostorState'
import {
  sanitizeChatText,
  sanitizeGuessText,
  validateChatText,
  validateGuessText,
} from './textRules'
import { IMPOSTOR_COMMANDS, type ImpostorCommand, type ImpostorDrawStrokePayload } from './types'

export type TrackImpostorCommand = (actionId: string, command: TrackedImpostorCommand) => void

export interface ImpostorCommands {
  drawStroke: (payload: ImpostorDrawStrokePayload) => boolean
  chat: (text: string) => boolean
  vote: (targetGuestId: string | null) => boolean
  guess: (text: string) => boolean
}

/**
 * Sends the impostor commands for the current phase (§3.11), each with a fresh `actionId` and the
 * current `phaseId`, and hands the `actionId` to the game's store so its ACK/error can be matched.
 * Every sender returns `false` instead of throwing while there is no phase or the socket is down;
 * the reconnect snapshot restores the authoritative state afterwards.
 */
export function useImpostorCommands(trackCommand: TrackImpostorCommand): ImpostorCommands {
  const client = useRealtimeClient()
  const phaseId = useGameSessionStore((s) => s.phaseId)

  const send = React.useCallback(
    (command: ImpostorCommand, tracked: TrackedImpostorCommand): boolean => {
      if (!phaseId) return false
      const actionId = generateUUID()
      try {
        client.sendCommand<ImpostorCommand['payload']>({ ...command, actionId, phaseId })
      } catch {
        return false
      }
      trackCommand(actionId, tracked)
      return true
    },
    [client, phaseId, trackCommand],
  )

  return React.useMemo(
    () => ({
      drawStroke: (payload) =>
        send(
          { type: IMPOSTOR_COMMANDS.drawStroke, payload },
          { type: IMPOSTOR_COMMANDS.drawStroke, strokeId: payload.strokeId },
        ),
      chat: (raw) => {
        if (validateChatText(raw) !== null) return false
        return send(
          { type: IMPOSTOR_COMMANDS.chat, payload: { text: sanitizeChatText(raw) } },
          { type: IMPOSTOR_COMMANDS.chat },
        )
      },
      vote: (targetGuestId) =>
        send(
          { type: IMPOSTOR_COMMANDS.vote, payload: { targetGuestId } },
          { type: IMPOSTOR_COMMANDS.vote, target: targetGuestId },
        ),
      guess: (raw) => {
        if (validateGuessText(raw) !== null) return false
        return send(
          { type: IMPOSTOR_COMMANDS.guess, payload: { text: sanitizeGuessText(raw) } },
          { type: IMPOSTOR_COMMANDS.guess },
        )
      },
    }),
    [send],
  )
}
