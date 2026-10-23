import { describe, expect, it } from 'vitest'

import { initialsFor } from './initials'

describe('initialsFor', () => {
  it('uses one letter, two on a clash, and the seat number when even that clashes', () => {
    const map = initialsFor([
      { guestId: 'a', nickname: 'rexy' },
      { guestId: 'b', nickname: 'Stego' },
      { guestId: 'c', nickname: 'Spinny' },
      { guestId: 'd', nickname: 'Sp ark' },
      { guestId: 'e', nickname: null },
    ])
    expect(map.get('a')).toBe('R')
    expect(map.get('b')).toBe('ST')
    expect(map.get('c')).toBe('S3')
    expect(map.get('d')).toBe('S4')
    expect(map.get('e')).toBe('?')
  })

  it('handles emoji-only and accented names', () => {
    const map = initialsFor([
      { guestId: 'a', nickname: '🦖🦖' },
      { guestId: 'b', nickname: 'Élodie' },
    ])
    expect(map.get('a')).toBe('?')
    expect(map.get('b')).toBe('É')
  })
})
