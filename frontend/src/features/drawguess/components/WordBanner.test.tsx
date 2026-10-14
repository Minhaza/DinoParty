import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { WordBanner } from './WordBanner'

describe('WordBanner', () => {
  it('displays drawer secret word for drawer', () => {
    render(
      <WordBanner
        phase="DRAWING"
        isDrawer={true}
        drawerNickname="Rex"
        word="VOLCANO"
        hint="_______"
        deadlineAt="2026-10-14T12:01:00.000Z"
        isSolved={false}
      />,
    )

    expect(screen.getByText('You')).toBeInTheDocument()
    expect(screen.getByText('Your word:')).toBeInTheDocument()
    expect(screen.getByText('VOLCANO')).toBeInTheDocument()
    expect(screen.getByText('(7 letters)')).toBeInTheDocument()
  })

  it('displays masked hints for guessers', () => {
    render(
      <WordBanner
        phase="DRAWING"
        isDrawer={false}
        drawerNickname="Rex"
        word={null}
        hint="V___A_O"
        deadlineAt="2026-10-14T12:01:00.000Z"
        isSolved={false}
      />,
    )

    expect(screen.getByText('Rex')).toBeInTheDocument()
    expect(screen.getByLabelText('Word hint: V___A_O')).toBeInTheDocument()
    expect(screen.getByText('V')).toBeInTheDocument()
    expect(screen.getByText('A')).toBeInTheDocument()
    expect(screen.getByText('O')).toBeInTheDocument()
  })

  it('displays revealed word during ROUND_END', () => {
    render(
      <WordBanner
        phase="ROUND_END"
        isDrawer={false}
        drawerNickname="Rex"
        word="VOLCANO"
        hint="_______"
        deadlineAt="2026-10-14T12:01:00.000Z"
        isSolved={false}
      />,
    )

    expect(screen.getByText('Word:')).toBeInTheDocument()
    expect(screen.getByText('VOLCANO')).toBeInTheDocument()
  })

  it('displays solved status when guesser solves word', () => {
    render(
      <WordBanner
        phase="DRAWING"
        isDrawer={false}
        drawerNickname="Rex"
        word="VOLCANO"
        hint="_______"
        deadlineAt="2026-10-14T12:01:00.000Z"
        isSolved={true}
      />,
    )

    expect(screen.getByText('You got it!')).toBeInTheDocument()
    expect(screen.getByText('VOLCANO')).toBeInTheDocument()
  })
})
