import { act, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { OUTCOME_COPY } from './copy'
import { CHAT_ERROR_COOLDOWN_MS, DiscussionChat } from './DiscussionChat'
import { ImpostorGuessInput } from './ImpostorGuessInput'
import { RoleCard } from './RoleCard'
import { RoundReveal } from './RoundReveal'
import type { ImpostorOutcome, ImpostorRoundEndedPayload } from './types'
import { VotePanel, type VoteCandidate } from './VotePanel'

const category = { key: 'animals', label: 'Animals' }

const candidates: VoteCandidate[] = [
  { guestId: 'me', nickname: 'Rexy' },
  { guestId: 'g2', nickname: 'Trixie' },
  { guestId: 'g3', nickname: 'Stego' },
  { guestId: 'g4', nickname: 'Ptera' },
]
const playersById = new Map(candidates.map((c) => [c.guestId, c]))

/** Tag and class of every element: what someone glancing at the screen could tell apart. */
function shape(el: Element): string[] {
  return [el, ...Array.from(el.querySelectorAll('*'))].map(
    (n) => `${n.tagName}.${n.getAttribute('class') ?? ''}`,
  )
}

describe('RoleCard', () => {
  it('DI4_shows the word to artists only', () => {
    const { rerender } = render(<RoleCard role={{ role: 'ARTIST', category, word: 'Turtle' }} />)
    expect(screen.getByText('Turtle')).toBeInTheDocument()
    expect(screen.getByText('Animals')).toBeInTheDocument()

    rerender(<RoleCard role={{ role: 'IMPOSTOR', category, word: null }} />)
    expect(screen.queryByText('Turtle')).not.toBeInTheDocument()
    expect(screen.getByText(/you're the impostor/i)).toBeInTheDocument()
    expect(screen.getByText('Animals')).toBeInTheDocument()

    rerender(<RoleCard role={null} />)
    expect(screen.queryByText('Turtle')).not.toBeInTheDocument()
    expect(screen.queryByText('Animals')).not.toBeInTheDocument()
    expect(screen.getByText(/you're watching/i)).toBeInTheDocument()
  })

  it('DI4_artist, impostor and spectator cards share one layout', () => {
    for (const compact of [false, true]) {
      const artist = render(
        <RoleCard compact={compact} role={{ role: 'ARTIST', category, word: 'Turtle' }} />,
      )
      const artistShape = shape(artist.getByTestId('role-card'))
      artist.unmount()
      const impostor = render(
        <RoleCard compact={compact} role={{ role: 'IMPOSTOR', category, word: null }} />,
      )
      expect(shape(impostor.getByTestId('role-card'))).toEqual(artistShape)
      impostor.unmount()
      const spectator = render(<RoleCard compact={compact} role={null} />)
      expect(shape(spectator.getByTestId('role-card'))).toEqual(artistShape)
      spectator.unmount()
    }
  })

  it('turns its fade-in off for reduced motion', () => {
    render(<RoleCard role={{ role: 'ARTIST', category, word: 'Turtle' }} />)
    expect(screen.getByTestId('role-card')).toHaveClass('motion-reduce:animate-none')
  })
})

describe('VotePanel', () => {
  const renderPanel = (props: Partial<React.ComponentProps<typeof VotePanel>> = {}) => {
    const onVote = vi.fn()
    const utils = render(
      <VotePanel
        candidates={candidates}
        currentGuestId="me"
        myVote={null}
        pendingVote={null}
        votedGuestIds={[]}
        enabled
        onVote={onVote}
        {...props}
      />,
    )
    return { onVote, ...utils }
  }

  it('DI9_offers everyone but the viewer', () => {
    renderPanel()
    const radios = screen.getAllByRole('radio')
    expect(radios).toHaveLength(3)
    expect(screen.queryByRole('radio', { name: /rexy/i })).not.toBeInTheDocument()
  })

  it('DI9_votes by keyboard', async () => {
    const user = userEvent.setup()
    const { onVote } = renderPanel()
    await user.tab()
    expect(screen.getByRole('radio', { name: /trixie/i })).toHaveFocus()
    await user.keyboard(' ')
    expect(onVote).toHaveBeenLastCalledWith('g2')
    await user.keyboard('{ArrowDown}')
    expect(onVote).toHaveBeenLastCalledWith('g3')
  })

  it('DI9_changes and withdraws a vote', async () => {
    const user = userEvent.setup()
    const { onVote } = renderPanel({ myVote: 'g2' })
    expect(screen.getByRole('radio', { name: /trixie/i })).toBeChecked()
    expect(screen.getByText(/you voted for/i)).toHaveTextContent('Trixie')
    await user.click(screen.getByRole('radio', { name: /ptera/i }))
    expect(onVote).toHaveBeenLastCalledWith('g4')
    await user.click(screen.getByRole('button', { name: /withdraw/i }))
    expect(onVote).toHaveBeenLastCalledWith(null)
  })

  it('DI9_shows who voted without colour alone, and a pending vote', () => {
    renderPanel({ votedGuestIds: ['g3'], pendingVote: { target: 'g4' } })
    const stego = screen.getByRole('radio', { name: /stego/i }).closest('label')
    expect(stego).not.toBeNull()
    expect(within(stego as HTMLElement).getByText('voted')).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /ptera/i })).toBeChecked()
    expect(screen.getByText(/sending your vote/i)).toBeInTheDocument()
  })

  it('is disabled outside the voting phase', async () => {
    const user = userEvent.setup()
    const { onVote } = renderPanel({ enabled: false })
    for (const radio of screen.getAllByRole('radio')) expect(radio).toBeDisabled()
    expect(screen.getByRole('button', { name: /withdraw/i })).toBeDisabled()
    await user.click(screen.getByRole('radio', { name: /trixie/i }))
    expect(onVote).not.toHaveBeenCalled()
  })

  it('tells a viewer outside the round that only players vote, not that voting is still to come', () => {
    renderPanel({ enabled: false })
    expect(screen.getByText(/only dinos playing this round can vote/i)).toBeInTheDocument()
    expect(screen.queryByText(/voting opens/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/you have not voted/i)).not.toBeInTheDocument()
  })
})

describe('DiscussionChat', () => {
  afterEach(() => vi.useRealTimers())

  const renderChat = (props: Partial<React.ComponentProps<typeof DiscussionChat>> = {}) => {
    const onSend = vi.fn(() => true)
    const utils = render(
      <DiscussionChat
        messages={[]}
        playersById={playersById}
        currentGuestId="me"
        enabled
        onSend={onSend}
        {...props}
      />,
    )
    return { onSend, ...utils }
  }

  it('DI8_is only there while voting', () => {
    renderChat({ enabled: false })
    expect(screen.queryByRole('region', { name: /discussion/i })).not.toBeInTheDocument()
  })

  it('DI8_renders messages as text, never HTML, in a live region', () => {
    renderChat({ messages: [{ guestId: 'g2', text: '<b>bold</b> <img src=x>', ts: 't1' }] })
    const log = screen.getByRole('list', { name: /discussion messages/i })
    expect(log).toHaveAttribute('aria-live', 'polite')
    expect(log).toHaveTextContent('Trixie: <b>bold</b> <img src=x>')
    expect(log.querySelector('b')).toBeNull()
    expect(log.querySelector('img')).toBeNull()
  })

  it('DI8_enforces 1–120 characters', async () => {
    const user = userEvent.setup()
    const { onSend } = renderChat()
    const send = screen.getByRole('button', { name: /send/i })
    expect(send).toBeDisabled()
    const input = screen.getByRole('textbox', { name: /message/i })
    fireEvent.change(input, { target: { value: 'x'.repeat(121) } })
    expect(send).toBeDisabled()
    expect(screen.getByText('121/120')).toBeInTheDocument()
    fireEvent.change(input, { target: { value: '🦖'.repeat(120) } })
    expect(send).toBeEnabled()
    await user.click(send)
    expect(onSend).toHaveBeenCalledWith('🦖'.repeat(120))
    expect(input).toHaveValue('')
  })

  it('DI8_shows RATE_LIMITED and rests the send button', () => {
    vi.useFakeTimers()
    const { rerender, onSend } = renderChat()
    fireEvent.change(screen.getByRole('textbox', { name: /message/i }), {
      target: { value: 'hello' },
    })
    rerender(
      <DiscussionChat
        messages={[]}
        playersById={playersById}
        currentGuestId="me"
        enabled
        error="Easy there — wait a moment before sending another message."
        onSend={onSend}
      />,
    )
    act(() => vi.advanceTimersByTime(1))
    expect(screen.getByRole('status')).toHaveTextContent(/wait a moment/i)
    expect(screen.getByRole('button', { name: /send/i })).toBeDisabled()
    act(() => vi.advanceTimersByTime(CHAT_ERROR_COOLDOWN_MS))
    expect(screen.getByRole('button', { name: /send/i })).toBeEnabled()
  })

  it('DI8_rests again on a repeated RATE_LIMITED and never stays stuck when the error clears', () => {
    vi.useFakeTimers()
    const { rerender, onSend } = renderChat()
    fireEvent.change(screen.getByRole('textbox', { name: /message/i }), {
      target: { value: 'hello' },
    })
    const withError = (error: string | null) =>
      rerender(
        <DiscussionChat
          messages={[]}
          playersById={playersById}
          currentGuestId="me"
          enabled
          error={error}
          onSend={onSend}
        />,
      )
    const send = () => screen.getByRole('button', { name: /send/i })
    const limited = 'Easy there — wait a moment before sending another message.'

    withError(limited)
    act(() => vi.advanceTimersByTime(1))
    expect(send()).toBeDisabled()
    // The error clears mid-rest (e.g. a late ACK): Send comes back instead of staying disabled.
    withError(null)
    expect(send()).toBeEnabled()
    act(() => vi.advanceTimersByTime(CHAT_ERROR_COOLDOWN_MS))
    expect(send()).toBeEnabled()
    // The next send cleared the error, so the same text again is a new rest.
    withError(limited)
    act(() => vi.advanceTimersByTime(1))
    expect(send()).toBeDisabled()
  })
})

describe('ImpostorGuessInput', () => {
  const base = {
    impostorName: 'Stego',
    category,
    deadlineAt: '2099-01-01T00:00:30.000Z',
    submitted: false,
  }

  it('DI11_gives the caught impostor one guess', async () => {
    const user = userEvent.setup()
    const onGuess = vi.fn(() => true)
    const { rerender } = render(<ImpostorGuessInput {...base} isGuesser onGuess={onGuess} />)
    await user.type(screen.getByRole('textbox', { name: /your guess/i }), 'Turtle')
    await user.click(screen.getByRole('button', { name: /lock it in/i }))
    expect(onGuess).toHaveBeenCalledWith('Turtle')
    expect(screen.getByRole('button', { name: /lock it in/i })).toBeDisabled()

    rerender(<ImpostorGuessInput {...base} isGuesser submitted onGuess={onGuess} />)
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(screen.getByText(/guess locked in/i)).toBeInTheDocument()
  })

  it('DI11_shows everyone else the waiting screen and countdown', () => {
    render(<ImpostorGuessInput {...base} isGuesser={false} onGuess={vi.fn()} />)
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(screen.getByText(/the impostor is guessing/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/time remaining/i)).toBeInTheDocument()
  })
})

describe('RoundReveal', () => {
  const ended = (outcome: ImpostorOutcome, extra: Partial<ImpostorRoundEndedPayload> = {}) => ({
    round: 2,
    outcome,
    impostorGuestId: 'g3',
    category,
    word: 'Turtle',
    decoyWord: null,
    votes: { me: 'g3', g2: 'g3', g3: 'me' },
    guess: null,
    scoreDeltas: { me: 0, g2: 0, g3: 2, g4: 0 },
    scores: { me: 3, g2: 1, g3: 4, g4: 0 },
    ...extra,
  })

  it.each<ImpostorOutcome>(['ESCAPED', 'GUESSED', 'CAUGHT', 'IMPOSTOR_LEFT'])(
    'DI14_reveals the round for %s',
    (outcome) => {
      render(
        <RoundReveal
          roundEnd={ended(outcome, outcome === 'GUESSED' ? { guess: 'turtle' } : {})}
          players={candidates}
          currentGuestId="me"
          deadlineAt={null}
          totalRounds={4}
        />,
      )
      expect(
        screen.getByRole('heading', { name: OUTCOME_COPY[outcome].headline }),
      ).toBeInTheDocument()
      expect(screen.getAllByText('Turtle').length).toBeGreaterThan(0)
      expect(screen.getByText('Animals')).toBeInTheDocument()
      const table = screen.getByRole('table')
      const row = (name: RegExp | string) =>
        within(table).getByRole('rowheader', { name }).closest('tr') as HTMLElement
      const stegoRow = row(/^stego/i)
      expect(stegoRow).toHaveTextContent('(impostor)')
      expect(stegoRow).toHaveTextContent('+2')
      expect(row('You')).toHaveTextContent(/stego/i)
      const pteraRow = row('Ptera')
      expect(pteraRow).toHaveTextContent('No vote')
      expect(screen.getByText(outcome === 'GUESSED' ? 'turtle' : 'No guess')).toBeInTheDocument()
      expect(screen.queryByText(/decoy/i)).not.toBeInTheDocument()
    },
  )

  it('shows the decoy word when there was one (Sketchy Saurus)', () => {
    render(
      <RoundReveal
        roundEnd={ended('CAUGHT', { decoyWord: 'Tortoise' })}
        players={candidates}
        currentGuestId="me"
        deadlineAt={null}
      />,
    )
    expect(screen.getByText(/decoy word/i)).toBeInTheDocument()
    expect(screen.getByText('Tortoise')).toBeInTheDocument()
  })
})
