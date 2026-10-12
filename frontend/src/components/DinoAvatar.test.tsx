import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { DinoAvatar } from '@/components/DinoAvatar'
import { DINO_AVATARS } from '@/components/dinoAvatars'

describe('DinoAvatar', () => {
  it('renders with default archetype when no key is specified', () => {
    render(<DinoAvatar />)
    const img = screen.getByRole('img', { name: 'Rex dinosaur avatar' })
    expect(img).toBeInTheDocument()
  })

  it('renders custom alt text when provided', () => {
    render(<DinoAvatar avatarKey="dino-rex" alt="Host avatar" />)
    expect(screen.getByRole('img', { name: 'Host avatar' })).toBeInTheDocument()
  })

  it('renders each supported avatar archetype with accessible label', () => {
    for (const dino of DINO_AVATARS) {
      const { unmount } = render(<DinoAvatar avatarKey={dino.key} />)
      expect(screen.getByRole('img', { name: `${dino.name} dinosaur avatar` })).toBeInTheDocument()
      unmount()
    }
  })

  it('falls back safely to default avatar for invalid keys', () => {
    render(<DinoAvatar avatarKey="invalid-pterodactyl" />)
    expect(screen.getByRole('img', { name: 'Rex dinosaur avatar' })).toBeInTheDocument()
  })
})
