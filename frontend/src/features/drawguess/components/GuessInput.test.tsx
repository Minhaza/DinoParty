import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { GuessInput } from './GuessInput'

describe('GuessInput', () => {
  it('allows guess submission with validation', () => {
    const onSubmit = vi.fn()
    render(
      <GuessInput
        isDrawer={false}
        isSolved={false}
        isDrawingPhase={true}
        onSubmitGuess={onSubmit}
      />,
    )

    const input = screen.getByRole('textbox')
    const submitBtn = screen.getByRole('button', { name: /send guess/i })

    // Button disabled when empty
    expect(submitBtn).toBeDisabled()

    // Enter guess
    fireEvent.change(input, { target: { value: 'triceratops' } })
    expect(submitBtn).toBeEnabled()

    // Submit
    fireEvent.click(submitBtn)
    expect(onSubmit).toHaveBeenCalledWith('triceratops')
    expect(input).toHaveValue('')
  })

  it('rejects empty/whitespace guesses', () => {
    const onSubmit = vi.fn()
    render(
      <GuessInput
        isDrawer={false}
        isSolved={false}
        isDrawingPhase={true}
        onSubmitGuess={onSubmit}
      />,
    )

    const input = screen.getByRole('textbox')
    fireEvent.change(input, { target: { value: '   ' } })
    fireEvent.submit(input.closest('form')!)

    expect(onSubmit).not.toHaveBeenCalled()
    expect(screen.getByText('Please enter a guess')).toBeInTheDocument()
  })

  it('is disabled for drawer with clear message', () => {
    render(
      <GuessInput isDrawer={true} isSolved={false} isDrawingPhase={true} onSubmitGuess={vi.fn()} />,
    )

    const input = screen.getByRole('textbox')
    expect(input).toBeDisabled()
    expect(input).toHaveAttribute('placeholder', 'You are drawing this round')
  })

  it('shows solver chat placeholder for players who already guessed', () => {
    render(
      <GuessInput isDrawer={false} isSolved={true} isDrawingPhase={true} onSubmitGuess={vi.fn()} />,
    )

    const input = screen.getByRole('textbox')
    expect(input).toHaveAttribute('placeholder', 'Chat with other solvers…')
  })
})
