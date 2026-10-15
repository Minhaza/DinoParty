import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { PlayerDto } from '@/api/types'

import { drawProgress, sampleTelephoneStroke } from '../fixtures'
import { TelephoneAssignmentCard } from './TelephoneAssignmentCard'
import { TelephonePhaseHeader } from './TelephonePhaseHeader'
import { TelephonePromptEditor, type TelephonePromptEditorProps } from './TelephonePromptEditor'
import { TelephoneWaitingScreen } from './TelephoneWaitingScreen'

const players: PlayerDto[] = ['guest-1', 'guest-2', 'guest-3', 'guest-4'].map((guestId, i) => ({
  guestId,
  nickname: ['Rex', 'Trixie', 'Ptera', 'Steggy'][i],
  avatarKey: 'dino-rex',
  avatarColor: null,
  ready: true,
  connection: 'CONNECTED',
  isHost: i === 0,
  score: null,
}))

function mockCanvas() {
  HTMLCanvasElement.prototype.getContext = vi.fn().mockReturnValue(null)
}

describe('TelephonePromptEditor', () => {
  const renderEditor = (props: Partial<TelephonePromptEditorProps> = {}) => {
    const onSubmit = vi.fn()
    const onChange = vi.fn()
    const utils = render(
      <TelephonePromptEditor
        mode="prompt"
        value=""
        submission="editing"
        submitError={null}
        offline={false}
        syncing={false}
        onChange={onChange}
        onSubmit={onSubmit}
        {...props}
      />,
    )
    return { ...utils, onSubmit, onChange }
  }

  it('disables submit for empty input and shows the counter', () => {
    renderEditor({ value: '   ' })
    expect(screen.getByRole('button', { name: /send prompt/i })).toBeDisabled()
    expect(screen.getByText('0/140')).toBeInTheDocument()
  })

  it('TP14 counts Unicode code points and preserves non-English text', () => {
    const { onSubmit } = renderEditor({ value: '  恐竜が🦖ケーキを焼く  ' })
    expect(screen.getByText('10/140')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /send prompt/i }))
    expect(onSubmit).toHaveBeenCalledWith('恐竜が🦖ケーキを焼く')
  })

  it('TP14 blocks text over 140 code points with a message', () => {
    renderEditor({ value: 'a'.repeat(141) })
    expect(screen.getByRole('button', { name: /send prompt/i })).toBeDisabled()
    expect(screen.getByText(/140 characters or fewer/i)).toBeInTheDocument()
    expect(screen.getByRole('textbox')).toHaveAttribute('aria-invalid', 'true')
  })

  it('submits with Enter, but not while an IME composition is active', () => {
    const { onSubmit } = renderEditor({ value: 'a dino' })
    const box = screen.getByRole('textbox')
    fireEvent.keyDown(box, { key: 'Enter', isComposing: true })
    expect(onSubmit).not.toHaveBeenCalled()
    fireEvent.keyDown(box, { key: 'Enter' })
    expect(onSubmit).toHaveBeenCalledTimes(1)
  })

  it('locks while submitting, and shows a server rejection', () => {
    const { rerender, onSubmit, onChange } = renderEditor({
      value: 'a dino',
      submission: 'submitting',
    })
    expect(screen.getByRole('button', { name: /send prompt/i })).toBeDisabled()
    expect(screen.getByText(/sending/i)).toBeInTheDocument()

    rerender(
      <TelephonePromptEditor
        mode="prompt"
        value="a dino"
        submission="error"
        submitError="That entry was not accepted. Check it and try again."
        offline={false}
        syncing={false}
        onChange={onChange}
        onSubmit={onSubmit}
      />,
    )
    expect(screen.getByRole('alert')).toHaveTextContent(/not accepted/i)
    expect(screen.getByRole('button', { name: /send prompt/i })).toBeEnabled()
  })

  it('is read-only while reconnecting', () => {
    renderEditor({ value: 'a dino', offline: true })
    expect(screen.getByRole('button', { name: /send prompt/i })).toBeDisabled()
    expect(screen.getByRole('textbox')).toHaveAttribute('readonly')
    expect(screen.getByText(/reconnecting/i)).toBeInTheDocument()
  })
})

describe('TelephoneAssignmentCard', () => {
  beforeEach(mockCanvas)

  it('TP8 shows only the assigned sentence, without an author', () => {
    render(
      <TelephoneAssignmentCard
        source={{ kind: 'TEXT', text: 'A stegosaurus juggling teacups', placeholder: false }}
      />,
    )
    expect(screen.getByText(/stegosaurus juggling teacups/)).toBeInTheDocument()
    expect(screen.getByRole('region', { name: /your assignment/i })).not.toHaveTextContent(/by /i)
  })

  it('shows the assigned drawing read-only', () => {
    render(
      <TelephoneAssignmentCard
        source={{ kind: 'DRAWING', strokes: [sampleTelephoneStroke], placeholder: false }}
      />,
    )
    const canvas = screen.getByRole('img', { name: /drawing you need to describe/i })
    expect(canvas).toHaveClass('cursor-default')
  })

  it('TP5 tells the player when the source is a placeholder', () => {
    render(<TelephoneAssignmentCard source={{ kind: 'DRAWING', strokes: [], placeholder: true }} />)
    expect(screen.getByText(/nothing to go on/i)).toBeInTheDocument()
  })

  it('waits for the private task', () => {
    render(<TelephoneAssignmentCard source={undefined} />)
    expect(screen.getByText(/getting your assignment/i)).toBeInTheDocument()
  })
})

describe('TelephonePhaseHeader', () => {
  it('labels the step from the server step and total', () => {
    render(
      <TelephonePhaseHeader
        kind="DESCRIBE"
        step={2}
        totalSteps={6}
        deadlineAt="2099-01-01T00:00:00.000Z"
        submitted={false}
      />,
    )
    expect(
      screen.getByRole('heading', { name: 'Step 3 of 6 — Describe the drawing' }),
    ).toBeInTheDocument()
    expect(screen.getByLabelText(/time remaining/i)).toBeInTheDocument()
  })

  it('TP12 shows a syncing state once the local countdown runs out', () => {
    const onExpire = vi.fn()
    render(
      <TelephonePhaseHeader
        kind="DRAW"
        step={1}
        totalSteps={4}
        deadlineAt="2000-01-01T00:00:00.000Z"
        submitted={false}
        onExpire={onExpire}
      />,
    )
    expect(screen.getByText(/time's up — syncing/i)).toBeInTheDocument()
    expect(onExpire).toHaveBeenCalledTimes(1)
  })
})

describe('TelephoneWaitingScreen', () => {
  it('TP4 lists who has submitted by name only, with public counts', () => {
    render(
      <TelephoneWaitingScreen
        kind="DRAW"
        deadlineAt="2099-01-01T00:00:00.000Z"
        progress={drawProgress}
        players={players}
        currentGuestId="guest-3"
      />,
    )
    expect(screen.getByText(/masterpiece is on its way/i)).toBeInTheDocument()
    expect(screen.getByText('2 of 4 dinos done')).toBeInTheDocument()
    const list = screen.getByRole('list', { name: /submitted/i })
    expect(list).toHaveTextContent('Trixie')
    expect(list).toHaveTextContent('You')
    expect(list).not.toHaveTextContent('Rex')
    expect(screen.queryByText(/%/)).not.toBeInTheDocument()
  })
})
