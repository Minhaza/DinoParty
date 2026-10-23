import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { ClientCommand, GameSnapshot } from '@/api/types'
import type { RealtimeClient } from '@/features/realtime/realtimeClient'
import { RealtimeContext } from '@/features/realtime/realtimeContext'
import { useGameSessionStore } from '@/stores/gameSessionStore'

import type { DinoImpostorSnapshot } from '../common/types'
import { DinoImpostorStage } from './DinoImpostorStage'
import { useDinoImpostorStore } from './dinoImpostorStore'
import {
  DI_PLAYERS,
  drawingMyTurnArtist,
  drawingOtherTurnImpostor,
  drawingSpectator,
  guessAsArtist,
  guessAsImpostor,
  roleRevealArtist,
  roleRevealImpostor,
  roleRevealSpectator,
  roundEndSnapshot,
  SECRET_WORD,
  VIEWER_ARTIST,
  VIEWER_IMPOSTOR,
  VIEWER_SPECTATOR,
  voteArtist,
  voteImpostor,
  voteSpectator,
} from './fixtures'

const sendCommand = vi.fn((c: ClientCommand) => c.actionId ?? 'a')

function show(snapshot: DinoImpostorSnapshot, viewer: string) {
  useGameSessionStore.getState().applySnapshot(snapshot as unknown as GameSnapshot)
  useDinoImpostorStore.getState().applySnapshot(snapshot)
  return render(
    <RealtimeContext.Provider value={{ sendCommand } as unknown as RealtimeClient}>
      <DinoImpostorStage players={DI_PLAYERS} currentGuestId={viewer} />
    </RealtimeContext.Provider>,
  )
}

describe('DinoImpostorStage', () => {
  beforeEach(() => {
    useGameSessionStore.getState().reset()
    useDinoImpostorStore.getState().reset()
    sendCommand.mockClear()
    HTMLCanvasElement.prototype.getContext = vi.fn().mockReturnValue(null)
  })

  it('DI4_ROLE_REVEAL shows the word to artists only', () => {
    const artist = show(roleRevealArtist, VIEWER_ARTIST)
    expect(screen.getByRole('heading', { name: /check your secret/i })).toBeInTheDocument()
    expect(screen.getByText(SECRET_WORD)).toBeInTheDocument()
    artist.unmount()

    const impostor = show(roleRevealImpostor, VIEWER_IMPOSTOR)
    expect(screen.queryByText(SECRET_WORD)).not.toBeInTheDocument()
    expect(screen.getByText(/you're the impostor/i)).toBeInTheDocument()
    impostor.unmount()

    show(roleRevealSpectator, VIEWER_SPECTATOR)
    expect(screen.queryByText(SECRET_WORD)).not.toBeInTheDocument()
    expect(screen.queryByText('Animals')).not.toBeInTheDocument()
    expect(screen.getByText(/you're watching/i)).toBeInTheDocument()
  })

  it('DI6_DRAWING shows the turn strip, canvas and legend; the drawer gets the live canvas', () => {
    show(drawingMyTurnArtist, VIEWER_ARTIST)
    expect(screen.getByRole('region', { name: /turn order/i })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: /your turn, draw one stroke/i })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: /stroke colours/i })).toBeInTheDocument()
    expect(screen.queryByRole('radio')).not.toBeInTheDocument()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  })

  it('DI6_DRAWING is watch-only for others and spectators, with no word leaked', () => {
    const impostor = show(drawingOtherTurnImpostor, VIEWER_IMPOSTOR)
    expect(screen.getByRole('img', { name: /every stroke so far/i })).toBeInTheDocument()
    expect(screen.queryByText(SECRET_WORD)).not.toBeInTheDocument()
    impostor.unmount()

    show(drawingSpectator, VIEWER_SPECTATOR)
    expect(screen.getByRole('img', { name: /every stroke so far/i })).toBeInTheDocument()
    expect(screen.queryByText(SECRET_WORD)).not.toBeInTheDocument()
  })

  it('DI9_VOTE offers the vote and chat to players, and sends a vote with the phaseId', async () => {
    const user = userEvent.setup()
    show(voteArtist, VIEWER_ARTIST)
    expect(screen.getByRole('radio', { name: /ptera/i })).toBeChecked()
    expect(screen.getByRole('region', { name: /discussion/i })).toBeInTheDocument()
    await user.click(screen.getByRole('radio', { name: /stego/i }))
    expect(sendCommand).toHaveBeenLastCalledWith(
      expect.objectContaining({
        type: 'IMPOSTOR_VOTE',
        phaseId: voteArtist.phaseId,
        payload: { targetGuestId: 'di-g3' },
      }),
    )
  })

  it('DI9_VOTE: the impostor votes like anyone; spectators only watch', () => {
    const impostor = show(voteImpostor, VIEWER_IMPOSTOR)
    expect(screen.getAllByRole('radio')).toHaveLength(9)
    expect(screen.queryByText(SECRET_WORD)).not.toBeInTheDocument()
    impostor.unmount()

    show(voteSpectator, VIEWER_SPECTATOR)
    for (const radio of screen.getAllByRole('radio')) expect(radio).toBeDisabled()
    expect(screen.queryByRole('region', { name: /discussion/i })).not.toBeInTheDocument()
  })

  it('DI11_GUESS gives only the caught impostor the input', () => {
    const impostor = show(guessAsImpostor, VIEWER_IMPOSTOR)
    expect(screen.getByRole('textbox', { name: /your guess/i })).toBeInTheDocument()
    impostor.unmount()

    show(guessAsArtist, VIEWER_ARTIST)
    expect(screen.queryByRole('textbox', { name: /your guess/i })).not.toBeInTheDocument()
    expect(screen.getByText(/the impostor is guessing/i)).toBeInTheDocument()
    expect(screen.getByText(/pointed at/i)).toHaveTextContent('Ptera')
  })

  it('DI14_ROUND_END reveals the round to everyone, spectators included', () => {
    show(roundEndSnapshot('GUESSED', null), VIEWER_SPECTATOR)
    const reveal = screen.getByRole('region', { name: /cracked the word/i })
    expect(within(reveal).getAllByText(SECRET_WORD).length).toBeGreaterThan(0)
    expect(within(reveal).getByRole('table')).toBeInTheDocument()
  })
})
