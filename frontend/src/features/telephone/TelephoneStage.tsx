import * as React from 'react'
import { Loader2 } from 'lucide-react'

import type { PlayerDto } from '@/api/types'
import type { DrawStrokePayload } from '@/features/drawing/types'
import { useConnectionStore } from '@/features/realtime/connectionStore'
import { useRealtimeClient } from '@/features/realtime/realtimeContext'
import { useGameSessionStore } from '@/stores/gameSessionStore'
import { useRoomStore } from '@/stores/roomStore'

import { stepLabel } from './copy'
import { TelephoneDescriptionEditor } from './components/TelephoneDescriptionEditor'
import { TelephoneDrawingStage } from './components/TelephoneDrawingStage'
import { TelephonePhaseHeader } from './components/TelephonePhaseHeader'
import { TelephonePromptEditor } from './components/TelephonePromptEditor'
import { TelephoneWaitingScreen } from './components/TelephoneWaitingScreen'
import { TelephoneRevealView } from './reveal/TelephoneRevealView'
import { useTelephoneStore } from './telephoneStore'
import { codePointLength, sanitizeTelephoneText, TELEPHONE_TEXT_MAX } from './textRules'
import { TELEPHONE_COMMANDS, type TelephoneCommandType } from './types'

/** Debounce for `TELEPHONE_TEXT_DRAFT` (server limit 2/s): keeps half-typed text across a refresh or the deadline. */
const TEXT_DRAFT_DEBOUNCE_MS = 700

/**
 * How long a submission may wait for its `ACTION_ACK`/`GAME_ERROR`. Sent is not accepted, so the
 * button stays locked meanwhile; after this the player may retry and a snapshot settles the truth
 * (a retry of an accepted entry is answered `ALREADY_SUBMITTED`, which also means "submitted").
 */
export const SUBMIT_WATCHDOG_MS = 10_000

export interface TelephoneStageProps {
  players: PlayerDto[]
  currentGuestId: string | null | undefined
  /** Only the host's reveal controls are offered; the server enforces it ([TP-6]). */
  isHost?: boolean
}

export function TelephoneStage({ players, currentGuestId, isHost = false }: TelephoneStageProps) {
  const client = useRealtimeClient()
  const online = useConnectionStore((s) => s.status === 'CONNECTED')

  const phase = useGameSessionStore((s) => s.phase)
  const phaseId = useGameSessionStore((s) => s.phaseId)
  const deadlineAt = useGameSessionStore((s) => s.deadlineAt)

  const step = useTelephoneStore((s) => s.step)
  const totalSteps = useTelephoneStore((s) => s.totalSteps)
  const kind = useTelephoneStore((s) => s.kind)
  const workPhaseId = useTelephoneStore((s) => s.workPhaseId)
  const task = useTelephoneStore((s) => s.task)
  const progress = useTelephoneStore((s) => s.progress)
  const textDraft = useTelephoneStore((s) => s.textDraft)
  const strokes = useTelephoneStore((s) => s.strokes)
  const canvasFull = useTelephoneStore((s) => s.canvasFull)
  const strokeRejection = useTelephoneStore((s) => s.strokeRejection)
  const submission = useTelephoneStore((s) => s.submission)
  const submitError = useTelephoneStore((s) => s.submitError)
  const syncing = useTelephoneStore((s) => s.syncing)
  const trackCommand = useTelephoneStore((s) => s.trackCommand)
  const setTextDraft = useTelephoneStore((s) => s.setTextDraft)
  const addLocalStroke = useTelephoneStore((s) => s.addLocalStroke)
  const appendLocalPoints = useTelephoneStore((s) => s.appendLocalPoints)
  const undoLocalStroke = useTelephoneStore((s) => s.undoLocalStroke)
  const clearLocalStrokes = useTelephoneStore((s) => s.clearLocalStrokes)

  // The step whose local countdown ran out; inputs stay locked until the server moves on.
  const [expiredPhaseId, setExpiredPhaseId] = React.useState<string | null>(null)
  const expired = phaseId !== null && expiredPhaseId === phaseId
  const handleExpire = React.useCallback(() => setExpiredPhaseId(phaseId), [phaseId])

  // Only views of the phase the server says is current are rendered; anything else is stale.
  const inStep = kind !== null && workPhaseId !== null && workPhaseId === phaseId
  const listedAsSubmitted = Boolean(
    currentGuestId &&
    progress?.phaseId === phaseId &&
    progress.submittedGuestIds.includes(currentGuestId),
  )
  const submitted = submission === 'submitted' || listedAsSubmitted
  const locked = syncing || expired

  /**
   * Sends a Telephone command for the current phase. Returns `false` instead of throwing while the
   * socket is down: the reconnect snapshot restores the server-held draft afterwards.
   */
  const sendCommand = React.useCallback(
    (type: TelephoneCommandType, payload: object, strokeId?: string): boolean => {
      if (!phaseId) return false
      try {
        const actionId = client.sendCommand({ type, phaseId, payload })
        trackCommand(actionId, type, phaseId, strokeId)
        return true
      } catch {
        return false
      }
    },
    [client, phaseId, trackCommand],
  )

  React.useEffect(() => {
    if (submission !== 'submitting') return
    const timer = setTimeout(() => {
      if (useTelephoneStore.getState().expireSubmission()) {
        useRoomStore.getState().setNeedsResync(true)
      }
    }, SUBMIT_WATCHDOG_MS)
    return () => clearTimeout(timer)
  }, [submission, workPhaseId])

  // Draft sync: the server keeps the latest text so a refresh or the deadline never loses it.
  const lastSentDraftRef = React.useRef<{ phaseId: string | null; text: string }>({
    phaseId: null,
    text: '',
  })
  const isTextStep = inStep && (kind === 'PROMPT' || kind === 'DESCRIBE')
  React.useEffect(() => {
    if (!isTextStep || submitted || submission === 'submitting' || !online || locked) return
    const last = lastSentDraftRef.current
    if (last.phaseId === phaseId && last.text === textDraft) return
    if (last.phaseId !== phaseId && textDraft === '') return
    if (codePointLength(sanitizeTelephoneText(textDraft)) > TELEPHONE_TEXT_MAX) return
    const timer = setTimeout(() => {
      if (sendCommand(TELEPHONE_COMMANDS.textDraft, { text: textDraft })) {
        lastSentDraftRef.current = { phaseId, text: textDraft }
      }
    }, TEXT_DRAFT_DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [isTextStep, submitted, submission, online, locked, phaseId, textDraft, sendCommand])

  const canSend = () => {
    // Read the store directly: a second click in the same tick must not send a second submit.
    const current = useTelephoneStore.getState().submission
    return current !== 'submitting' && current !== 'submitted' && !submitted && !locked
  }

  const handleSubmitText = (text: string) => {
    if (!canSend()) return
    sendCommand(TELEPHONE_COMMANDS.submitText, { text })
  }

  const handleSubmitDrawing = () => {
    if (!canSend()) return
    sendCommand(TELEPHONE_COMMANDS.submitDrawing, {})
  }

  // Canvas command adapter: the shared engine emits Draw & Guess batches, Telephone sends the same
  // payload under its own type (api-contracts §3.10).
  const handleSendStrokeBatch = React.useCallback(
    (batch: DrawStrokePayload) => sendCommand(TELEPHONE_COMMANDS.drawStroke, batch, batch.strokeId),
    [sendCommand],
  )

  const handleUndo = () => {
    if (strokes.length === 0) return
    if (sendCommand(TELEPHONE_COMMANDS.drawUndo, {})) undoLocalStroke()
  }

  const handleClear = () => {
    if (strokes.length === 0) return
    if (sendCommand(TELEPHONE_COMMANDS.drawClear, {})) clearLocalStrokes()
  }

  const announcement = inStep && kind ? stepLabel(step, totalSteps, kind) : ''

  if (phase === 'REVEAL') {
    return <TelephoneRevealView players={players} currentGuestId={currentGuestId} isHost={isHost} />
  }

  return (
    <div
      role="region"
      aria-label="Dino Telephone gameplay stage"
      className="mx-auto flex w-full min-w-0 flex-1 flex-col gap-3"
    >
      <p className="sr-only" aria-live="polite">
        {announcement}
      </p>

      {!inStep || !kind ? (
        <HoldingPanel />
      ) : (
        <>
          <TelephonePhaseHeader
            kind={kind}
            step={step}
            totalSteps={totalSteps}
            deadlineAt={deadlineAt}
            showDrawSource={kind === 'DRAW' && !submitted}
            drawSource={task ? task.source : undefined}
            onExpire={handleExpire}
          />

          {submitted ? (
            <TelephoneWaitingScreen
              kind={kind}
              deadlineAt={deadlineAt}
              progress={progress}
              players={players}
              currentGuestId={currentGuestId}
            />
          ) : kind === 'PROMPT' ? (
            <div className="mx-auto w-full max-w-2xl">
              <TelephonePromptEditor
                mode="prompt"
                value={textDraft}
                submission={submission}
                submitError={submitError}
                offline={!online}
                syncing={locked}
                onChange={setTextDraft}
                onSubmit={handleSubmitText}
              />
            </div>
          ) : kind === 'DRAW' ? (
            <TelephoneDrawingStage
              source={task ? task.source : undefined}
              strokes={strokes}
              canvasFull={canvasFull}
              strokeRejection={strokeRejection}
              submission={submission}
              submitError={submitError}
              offline={!online}
              syncing={locked}
              onSendStrokeBatch={handleSendStrokeBatch}
              onLocalStrokeStart={addLocalStroke}
              onLocalPoints={appendLocalPoints}
              onUndo={handleUndo}
              onClear={handleClear}
              onSubmit={handleSubmitDrawing}
            />
          ) : (
            <TelephoneDescriptionEditor
              source={task ? task.source : undefined}
              value={textDraft}
              submission={submission}
              submitError={submitError}
              offline={!online}
              syncing={locked}
              onChange={setTextDraft}
              onSubmit={handleSubmitText}
            />
          )}
        </>
      )}
    </div>
  )
}

/** Between steps or before the first task. */
function HoldingPanel() {
  return (
    <section
      role="status"
      className="mx-auto flex w-full max-w-xl flex-col items-center gap-3 rounded-3xl border border-border bg-card px-5 py-10 text-center shadow-xs"
    >
      <Loader2 className="size-6 text-primary motion-safe:animate-spin" aria-hidden="true" />
      <p className="font-fredoka text-xl font-semibold text-foreground">
        Getting the next step ready…
      </p>
    </section>
  )
}
