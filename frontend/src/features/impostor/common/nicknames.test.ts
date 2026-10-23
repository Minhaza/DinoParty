import { describe, expect, it } from 'vitest'
import { rosterNames } from './nicknames'

describe('rosterNames', () => {
  it('combines active roster players with nicknames map where active roster wins', () => {
    const players = [
      { guestId: 'p1', nickname: 'Rexy' },
      { guestId: 'p2', nickname: 'Trixie New' },
    ]
    const nicknames = {
      p2: 'Trixie Old',
      p3: 'Stego',
      p4: 'Bronto',
    }

    const map = rosterNames(players, nicknames)
    expect(map.get('p1')?.nickname).toBe('Rexy')
    expect(map.get('p2')?.nickname).toBe('Trixie New')
    expect(map.get('p3')?.nickname).toBe('Stego')
    expect(map.get('p4')?.nickname).toBe('Bronto')
    expect(map.get('p5')).toBeUndefined()
  })

  it('handles empty players and undefined nicknames', () => {
    const map = rosterNames([])
    expect(map.size).toBe(0)
  })
})
