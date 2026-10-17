import * as React from 'react'

import type { PlayerDto } from '@/api/types'
import { useConnectionStore } from '@/features/realtime/connectionStore'
import { useRealtimeClient } from '@/features/realtime/realtimeContext'
import { useGameSessionStore } from '@/stores/gameSessionStore'
import { useRoomStore } from '@/stores/roomStore'

import {
  canBrowseNext,
  canBrowsePrev,
  canHostPrev,
  entryKey,
  isLastPosition,
  positionLabel,
  shownPosition,
  toLinear,
  visibleEntries,
} from '../revealSelectors'
import { useRevealStore } from '../revealStore'
import { AUTO_REVEAL_SPEEDS, TELEPHONE_COMMANDS } from '../types'
import { TelephoneAlbumViewer } from './TelephoneAlbumViewer'
import { entryVerb, resolveContributor } from './albumCopy'
import { TelephoneRevealControls } from './TelephoneRevealControls'
import { TelephoneRevealProgress } from './TelephoneRevealProgress'

/** A host move that gets no answer in this time unlocks the controls again (a resync follows). */
export const NAV_WATCHDOG_MS = 5_000

export interface TelephoneRevealViewProps {
  players: PlayerDto[]
  currentGuestId: string | null | undefined
  isHost: boolean
}

/**
 * The live album reveal ([TP-6]). Everything shown comes from the reveal store, i.e. from what the
 * server has published; the host's buttons send the shared cursor, never the locally browsed one,
 * and the server decides (stale cursors and non-hosts are rejected there, not hidden here).
 */
export function TelephoneRevealView({ players, isHost }: TelephoneRevealViewProps) {
  const client = useRealtimeClient()
  const online = useConnectionStore((s) => s.status === 'CONNECTED')
  const order = useGameSessionStore((s) => s.order)
  const reveal = useRevealStore()

  const shown = shownPosition(reveal)
  const entries = visibleEntries(reveal)
  const label = positionLabel(reveal)
  const perChain = reveal.entriesPerChain
  const highWaterLinear = reveal.highWater ? toLinear(reveal.highWater, perChain) : -1
  const atLast = isLastPosition(reveal)

  const [autoReveal, setAutoReveal] = React.useState(false)
  const [autoSpeed, setAutoSpeed] = React.useState<number>(5)
  const [autoSecondsLeft, setAutoSecondsLeft] = React.useState<number>(5)

  const autoActive = autoReveal && !atLast
  const cursorKey = reveal.cursor ? `${reveal.cursor.chainIndex}:${reveal.cursor.entryIndex}` : null

  const sendMove = React.useCallback(
    (type: typeof TELEPHONE_COMMANDS.revealNext | typeof TELEPHONE_COMMANDS.revealPrev) => {
      const state = useRevealStore.getState()
      // Read the store directly: a second click in the same tick must not send a second move.
      if (state.pendingActionId || !state.phaseId || !state.cursor) return
      try {
        const actionId = client.sendCommand({
          type,
          phaseId: state.phaseId,
          payload: { chainIndex: state.cursor.chainIndex, entryIndex: state.cursor.entryIndex },
        })
        state.trackNav(actionId)
      } catch {
        // Offline: the reconnect snapshot brings the current position back.
      }
    },
    [client],
  )

  // Timer interval for auto reveal
  React.useEffect(() => {
    if (!autoActive || !isHost || !online) return

    let left = autoSpeed
    const interval = setInterval(() => {
      left -= 1
      if (left <= 0) {
        const state = useRevealStore.getState()
        if (!state.pendingActionId) {
          sendMove(TELEPHONE_COMMANDS.revealNext)
          left = autoSpeed
          setAutoSecondsLeft(autoSpeed)
        }
      } else {
        setAutoSecondsLeft(left)
      }
    }, 1000)

    return () => clearInterval(interval)
  }, [autoActive, isHost, online, autoSpeed, cursorKey, sendMove])

  const handleToggleAutoReveal = () => {
    if (autoActive) {
      setAutoReveal(false)
    } else {
      setAutoSecondsLeft(autoSpeed)
      setAutoReveal(true)
    }
  }

  const handleSpeedChange = (speed: number) => {
    setAutoSpeed(speed)
    setAutoSecondsLeft(speed)
  }

  const handleHostPrev = () => {
    setAutoReveal(false)
    sendMove(TELEPHONE_COMMANDS.revealPrev)
  }

  React.useEffect(() => {
    if (!reveal.pendingActionId) return
    const timer = setTimeout(() => {
      useRevealStore.getState().expireNav()
      useRoomStore.getState().setNeedsResync(true)
    }, NAV_WATCHDOG_MS)
    return () => clearTimeout(timer)
  }, [reveal.pendingActionId])

  // The live position is announced; local browsing is the player's own and stays quiet.
  const liveEntry = reveal.cursor
    ? reveal.entries[entryKey(reveal.cursor.chainIndex, reveal.cursor.entryIndex)]
    : undefined
  const liveLabel = positionLabel({ ...reveal, browse: null })
  const announcement = liveEntry
    ? `${liveLabel}: ${resolveContributor(liveEntry.authorGuestId, liveEntry.authorNickname, players).nickname} ${entryVerb(liveEntry)}`
    : liveLabel

  if (!shown || perChain === 0) {
    return (
      <section
        role="status"
        className="mx-auto w-full max-w-xl rounded-3xl border border-border bg-card px-5 py-10 text-center shadow-xs"
      >
        <p className="font-fredoka text-xl font-semibold text-foreground">Opening the albums…</p>
      </section>
    )
  }

  const chain = shown.chainIndex
  const ownerGuestId = order[chain] ?? reveal.entries[entryKey(chain, 0)]?.authorGuestId ?? null
  const ownerNickname = reveal.entries[entryKey(chain, 0)]?.authorNickname ?? null
  const following = reveal.browse === null
  const revealedPerAlbum = Array.from({ length: reveal.chains }, (_, c) =>
    Math.max(0, Math.min(perChain, highWaterLinear - c * perChain + 1)),
  )

  const browseAlbum = (target: number) => {
    if (target < 0 || target * perChain > highWaterLinear) return
    const last = Math.min(perChain - 1, highWaterLinear - target * perChain)
    reveal.browseTo({ chainIndex: target, entryIndex: last })
  }

  const handleKeyDown = (event: React.KeyboardEvent) => {
    if (event.target instanceof HTMLInputElement || event.target instanceof HTMLTextAreaElement)
      return
    if (event.key === 'ArrowLeft') {
      event.preventDefault()
      reveal.browsePrev()
    } else if (event.key === 'ArrowRight') {
      event.preventDefault()
      reveal.browseNext()
    }
  }

  return (
    <section
      role="region"
      aria-label="Album reveal"
      tabIndex={-1}
      onKeyDown={handleKeyDown}
      className="mx-auto flex w-full max-w-2xl min-w-0 flex-col gap-4 outline-none"
    >
      <p className="sr-only" aria-live="polite" data-testid="reveal-announcer">
        {announcement}
      </p>

      <TelephoneRevealProgress
        label={label}
        chains={reveal.chains}
        entriesPerChain={perChain}
        revealedPerAlbum={revealedPerAlbum}
        shownAlbum={chain}
        autoAdvanceAt={reveal.autoAdvanceAt}
      />

      {reveal.navError && (
        <p
          role="alert"
          className="rounded-xl bg-destructive/10 px-3 py-2 text-sm font-medium text-destructive"
        >
          {reveal.navError}
        </p>
      )}

      <TelephoneAlbumViewer
        ownerGuestId={ownerGuestId}
        ownerNickname={ownerNickname}
        entries={entries}
        players={players}
        highlightStep={shown.entryIndex}
        arrivingStep={following ? shown.entryIndex : null}
        subtitle={following ? undefined : 'You are looking back — the reveal goes on without you.'}
      />

      <TelephoneRevealControls
        isHost={isHost}
        canHostPrev={canHostPrev(reveal)}
        hostBusy={reveal.pendingActionId !== null}
        offline={!online}
        atLastEntry={atLast}
        onHostPrev={handleHostPrev}
        onHostNext={() => sendMove(TELEPHONE_COMMANDS.revealNext)}
        canBrowsePrev={canBrowsePrev(reveal)}
        canBrowseNext={canBrowseNext(reveal)}
        canPrevAlbum={chain > 0}
        canNextAlbum={(chain + 1) * perChain <= highWaterLinear}
        browsing={!following}
        onBrowsePrev={reveal.browsePrev}
        onBrowseNext={reveal.browseNext}
        onPrevAlbum={() => browseAlbum(chain - 1)}
        onNextAlbum={() => browseAlbum(chain + 1)}
        onFollowLive={reveal.followLive}
        autoReveal={autoActive}
        autoSpeed={autoSpeed}
        autoSecondsLeft={autoSecondsLeft}
        speeds={AUTO_REVEAL_SPEEDS}
        onToggleAutoReveal={handleToggleAutoReveal}
        onSpeedChange={handleSpeedChange}
      />
    </section>
  )
}
