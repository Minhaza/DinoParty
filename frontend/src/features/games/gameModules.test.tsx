import { render } from '@testing-library/react'

import { GameBanner } from './GameBanner'

import { gameModule, gameVisual } from './gameModules'

describe('frontend game modules', () => {
  it('knows the games this client can play', () => {
    expect(gameModule('draw-guess')?.playable).toBe(true)
    expect(gameModule('telephone')?.playable).toBe(true)
    expect(gameModule('mystery')).toBeNull()
  })

  it('lets Draw & Guess own its word settings in the lobby', () => {
    expect(gameModule('draw-guess')?.lobbyPanelFields).toEqual([
      'difficulty',
      'wordSource',
      'wordCategories',
      'wordPackIds',
    ])
    expect(gameModule('telephone')?.lobbyPanelFields).toEqual([])
  })

  it('gives unknown icon keys a neutral visual instead of failing', () => {
    expect(gameVisual('draw-guess').tone).toBe('draw')
    expect(gameVisual('telephone').tone).toBe('phone')
    expect(gameVisual('mystery').tone).toBe('neutral')
  })

  it('renders the painted banner for known games and a fallback for unknown ones', () => {
    const known = render(<GameBanner iconKey="telephone" />)
    expect(known.container.querySelector('img')?.getAttribute('src')).toContain('telephone')

    const unknown = render(<GameBanner iconKey="mystery" />)
    expect(unknown.container.querySelector('img')).toBeNull()
    expect(unknown.container.querySelector('svg')).not.toBeNull()
  })
})
