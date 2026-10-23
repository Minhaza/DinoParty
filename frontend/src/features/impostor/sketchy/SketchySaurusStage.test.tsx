import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { ClientCommand, GameSnapshot } from '@/api/types'
import type { RealtimeClient } from '@/features/realtime/realtimeClient'
import { RealtimeContext } from '@/features/realtime/realtimeContext'
import { useGameSessionStore } from '@/stores/gameSessionStore'

import {
  ARTIST_ROLE,
  AWARE_IMPOSTOR_ROLE,
  DECOY_WORD,
  maxGallery,
  SECRET_WORD,
  sketchySnapshot,
  SS_PLAYERS,
  SS_PLAYERS_10,
  SS_ROSTER_10,
  SS_ROSTER,
  UNAWARE_IMPOSTOR_ROLE,
  VIEWER_ARTIST,
  VIEWER_IMPOSTOR,
} from './fixtures'
import { SketchySaurusStage } from './SketchySaurusStage'
import { useSketchySaurusStore } from './sketchySaurusStore'
import type { SketchySaurusSnapshot } from './types'

const sendCommand = vi.fn((c: ClientCommand) => c.actionId ?? 'a')

function show(snapshot: SketchySaurusSnapshot, viewer: string) {
  useGameSessionStore.getState().applySnapshot({
    ...snapshot,
    gameKey: 'sketchy-saurus',
    players: SS_ROSTER,
  } as unknown as GameSnapshot)
  useSketchySaurusStore.getState().applySnapshot(snapshot)
  return render(
    <RealtimeContext.Provider value={{ sendCommand } as unknown as RealtimeClient}>
      <SketchySaurusStage players={SS_PLAYERS} currentGuestId={viewer} />
    </RealtimeContext.Provider>,
  )
}

describe('SketchySaurusStage', () => {
  beforeEach(() => {
    useGameSessionStore.getState().reset()
    useSketchySaurusStore.getState().reset()
    sendCommand.mockClear()
    HTMLCanvasElement.prototype.getContext = vi.fn().mockReturnValue(null)
  })

  it('SS1_ROLE_REVEAL shows secret word to artists, impostor alert to aware impostor, and decoy to unaware impostor', () => {
    const artist = show(sketchySnapshot('ROLE_REVEAL', ARTIST_ROLE), VIEWER_ARTIST)
    expect(screen.getByText(SECRET_WORD)).toBeInTheDocument()
    artist.unmount()

    const aware = show(sketchySnapshot('ROLE_REVEAL', AWARE_IMPOSTOR_ROLE), VIEWER_IMPOSTOR)
    expect(screen.queryByText(SECRET_WORD)).not.toBeInTheDocument()
    expect(screen.getByText(/You're the Impostor/i)).toBeInTheDocument()
    aware.unmount()

    const unaware = show(sketchySnapshot('ROLE_REVEAL', UNAWARE_IMPOSTOR_ROLE), VIEWER_IMPOSTOR)
    expect(screen.queryByText(SECRET_WORD)).not.toBeInTheDocument()
    expect(screen.getByText(DECOY_WORD)).toBeInTheDocument()
    unaware.unmount()
  })

  it('SS2_SKETCH shows drawing canvas when unsubmitted and waiting screen when submitted', () => {
    const unsubmitted = show(
      sketchySnapshot('SKETCH', ARTIST_ROLE, { submitted: false }),
      VIEWER_ARTIST,
    )
    expect(screen.getByRole('button', { name: /Submit sketch/i })).toBeInTheDocument()
    expect(screen.getByLabelText(/Drawing controls/i)).toBeInTheDocument()
    unsubmitted.unmount()

    show(sketchySnapshot('SKETCH', ARTIST_ROLE, { submitted: true }), VIEWER_ARTIST)
    expect(screen.getByText('Sketch submitted!')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Submit sketch/i })).not.toBeInTheDocument()
  })

  it('SS3_GALLERY shows drawings and allows voting with native radio buttons', async () => {
    const user = userEvent.setup()
    show(sketchySnapshot('GALLERY', ARTIST_ROLE), VIEWER_ARTIST)

    expect(screen.getByText('The Gallery · Spot the Impostor!')).toBeInTheDocument()
    expect(screen.getByRole('region', { name: /Discussion/i })).toBeInTheDocument()

    // 4 radio inputs (5 players minus viewer)
    const radios = screen.getAllByRole('radio')
    expect(radios).toHaveLength(4)

    await user.click(radios[0])
    expect(sendCommand).toHaveBeenCalledWith(
      expect.objectContaining({
        type: 'IMPOSTOR_VOTE',
        payload: { targetGuestId: 'ss-g2' },
      }),
    )
  })

  it('SS3_GUESS shows guess input to caught impostor and waiting to artist', () => {
    // Viewer is artist (ss-g1), caught impostor is ss-g2
    const artist = show(sketchySnapshot('GUESS', ARTIST_ROLE), VIEWER_ARTIST)
    expect(screen.getByText(/The impostor is guessing the word/i)).toBeInTheDocument()
    expect(screen.queryByPlaceholderText(/Enter the secret word/i)).not.toBeInTheDocument()
    artist.unmount()

    // Viewer is caught impostor (ss-g2)
    show(sketchySnapshot('GUESS', AWARE_IMPOSTOR_ROLE), VIEWER_IMPOSTOR)
    expect(screen.getByLabelText(/Your guess/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Lock it in/i })).toBeInTheDocument()
  })

  it('SS3_ROUND_END shows round outcome, decoy word, votes and score deltas', () => {
    show(sketchySnapshot('ROUND_END', ARTIST_ROLE), VIEWER_ARTIST)

    expect(screen.getByText('Impostor caught!')).toBeInTheDocument()
    expect(screen.getByText("The impostor's decoy word")).toBeInTheDocument()
    expect(screen.getByText(DECOY_WORD)).toBeInTheDocument()
  })

  it('SS3_UNAWARE_GUESS shows the "you were the impostor" moment, then the guess input', () => {
    show(sketchySnapshot('GUESS', UNAWARE_IMPOSTOR_ROLE), VIEWER_IMPOSTOR)
    expect(screen.getByRole('heading', { name: /you were the impostor/i })).toBeInTheDocument()
    expect(screen.getByLabelText(/Your guess/i)).toBeInTheDocument()
  })

  it('SS3_AWARE_GUESS has no "you were the impostor" moment: they already knew', () => {
    show(sketchySnapshot('GUESS', AWARE_IMPOSTOR_ROLE), VIEWER_IMPOSTOR)
    expect(screen.queryByRole('heading', { name: /you were the impostor/i })).toBeNull()
  })

  it('SS3_UNAWARE_NO_GUESS goes from GALLERY straight to the reveal, with no guess input', () => {
    show(sketchySnapshot('ROUND_END', UNAWARE_IMPOSTOR_ROLE), VIEWER_IMPOSTOR)
    expect(screen.queryByLabelText(/Your guess/i)).toBeNull()
    expect(screen.queryByRole('heading', { name: /you were the impostor/i })).toBeNull()
    expect(screen.getByText('Impostor caught!')).toBeInTheDocument()
  })

  it('G2 names a gallery artist who left from the snapshot nicknames', () => {
    const snapshot = sketchySnapshot('GALLERY', ARTIST_ROLE)
    useGameSessionStore.getState().applySnapshot({
      ...snapshot,
      gameKey: 'sketchy-saurus',
      players: SS_ROSTER,
    } as unknown as GameSnapshot)
    useSketchySaurusStore.getState().applySnapshot(snapshot)
    const leaver = SS_PLAYERS[3]
    render(
      <RealtimeContext.Provider value={{ sendCommand } as unknown as RealtimeClient}>
        <SketchySaurusStage
          players={SS_PLAYERS.filter((p) => p.guestId !== leaver.guestId)}
          currentGuestId={VIEWER_ARTIST}
        />
      </RealtimeContext.Provider>,
    )
    expect(screen.getByText(leaver.nickname)).toBeInTheDocument()
    expect(screen.queryByText(/Unknown Dino/i)).toBeNull()
  })

  it('SS3_GALLERY_MAX renders a full 10-drawing gallery, the last one empty', () => {
    const snapshot = sketchySnapshot('GALLERY', ARTIST_ROLE, {
      gallery: maxGallery,
      nicknames: Object.fromEntries(SS_PLAYERS_10.map((p) => [p.guestId, p.nickname])),
    })
    useGameSessionStore.getState().applySnapshot({
      ...snapshot,
      gameKey: 'sketchy-saurus',
      players: SS_ROSTER_10,
    } as unknown as GameSnapshot)
    useSketchySaurusStore.getState().applySnapshot(snapshot)
    render(
      <RealtimeContext.Provider value={{ sendCommand } as unknown as RealtimeClient}>
        <SketchySaurusStage players={SS_PLAYERS_10} currentGuestId={VIEWER_ARTIST} />
      </RealtimeContext.Provider>,
    )
    expect(screen.getAllByRole('radio')).toHaveLength(9)
    expect(screen.getAllByText(/No drawing submitted/i)).toHaveLength(1)
  })
})
