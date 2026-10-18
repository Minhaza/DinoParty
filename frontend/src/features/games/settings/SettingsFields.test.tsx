import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'

import type { GameDetailDto, SettingsMap } from '@/api/types'
import { DRAW_GUESS_DETAIL, TELEPHONE_DETAIL } from '@/test/mockApi'

import { SettingsFields } from './SettingsFields'

const DG = DRAW_GUESS_DETAIL as unknown as GameDetailDto
const TP = TELEPHONE_DETAIL as unknown as GameDetailDto

function Harness({
  game,
  claimed = [],
  errors = {},
  onSettings,
}: {
  game: GameDetailDto
  claimed?: string[]
  errors?: Record<string, string>
  onSettings?: (s: SettingsMap) => void
}) {
  const [settings, setSettings] = useState<SettingsMap>(game.defaultSettings)
  return (
    <SettingsFields
      schema={game.settingsSchema}
      claimedKeys={claimed}
      values={settings}
      errorFor={(key) => errors[key] ?? null}
      onChange={(key, value) => {
        const next = { ...settings, [key]: value }
        setSettings(next)
        onSettings?.(next)
      }}
    />
  )
}

describe('SettingsFields', () => {
  it('renders the server schema with its labels, defaults and bounds', () => {
    render(<Harness game={DG} claimed={['difficulty', 'wordSource']} />)

    expect(screen.getByLabelText('Turns per player')).toHaveValue('2')
    expect(screen.getByLabelText('Drawing time')).toHaveValue('80')
    expect(screen.getByLabelText('Word choice time')).toHaveValue('15')
    expect(screen.getByRole('checkbox', { name: 'Letter hints' })).toBeChecked()
    expect(screen.queryByLabelText('Difficulty')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Word categories')).not.toBeInTheDocument()
    const seconds = Array.from(
      screen.getByLabelText('Drawing time').querySelectorAll('option'),
    ).map((o) => Number(o.value))
    expect(Math.min(...seconds)).toBe(30)
    expect(Math.max(...seconds)).toBe(180)
  })

  it('reports typed values for int, bool and enum fields', async () => {
    const onSettings = vi.fn()
    render(<Harness game={DG} onSettings={onSettings} />)
    const user = userEvent.setup()

    await user.selectOptions(screen.getByLabelText('Drawing time'), '120')
    expect(onSettings).toHaveBeenLastCalledWith(expect.objectContaining({ drawSeconds: 120 }))

    await user.click(screen.getByRole('checkbox', { name: 'Letter hints' }))
    expect(onSettings).toHaveBeenLastCalledWith(expect.objectContaining({ hintReveal: false }))

    await user.selectOptions(screen.getByLabelText('Difficulty'), 'hard')
    expect(onSettings).toHaveBeenLastCalledWith(expect.objectContaining({ difficulty: 'hard' }))
  })

  it('offers the automatic value and hides a fixed bool', () => {
    render(<Harness game={TP} />)

    const entries = screen.getByLabelText('Entries per album')
    expect(entries).toHaveValue('0')
    expect(entries.querySelector('option')).toHaveTextContent('Auto')
    expect(screen.queryByText('Skip on timeout')).not.toBeInTheDocument()
  })

  it('shows a server error under its own field', () => {
    render(<Harness game={TP} errors={{ drawSeconds: 'must be between 30 and 180' }} />)

    const field = screen.getByLabelText('Drawing time')
    expect(field).toHaveAttribute('aria-invalid', 'true')
    expect(field).toHaveAccessibleDescription('must be between 30 and 180')
    expect(screen.getByLabelText('Prompt time')).not.toHaveAttribute('aria-invalid')
  })
})
