import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { WordSelectionModal } from './WordSelectionModal'

describe('WordSelectionModal', () => {
  it('renders 3 word choices for drawer and invokes callback on selection', () => {
    const onChoose = vi.fn()
    render(
      <WordSelectionModal
        isDrawer={true}
        drawerNickname="Rex"
        choices={['VELOCIRAPTOR', 'VOLCANO', 'FOSSIL']}
        deadlineAt="2026-10-14T12:00:15.000Z"
        onChooseWord={onChoose}
      />,
    )

    expect(screen.getByRole('heading', { name: 'Pick Your Word!' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'VELOCIRAPTOR' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'VOLCANO' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'FOSSIL' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'VOLCANO' }))
    expect(onChoose).toHaveBeenCalledWith(1)
  })

  it('renders waiting state for non-drawer', () => {
    render(
      <WordSelectionModal
        isDrawer={false}
        drawerNickname="Rex"
        choices={null}
        deadlineAt="2026-10-14T12:00:15.000Z"
        onChooseWord={vi.fn()}
      />,
    )

    expect(screen.getByRole('heading', { name: 'Round Starting' })).toBeInTheDocument()
    expect(screen.getByText('Rex is choosing a word to draw…')).toBeInTheDocument()
    expect(screen.getByText('Get ready to guess what Rex draws!')).toBeInTheDocument()
  })
})
