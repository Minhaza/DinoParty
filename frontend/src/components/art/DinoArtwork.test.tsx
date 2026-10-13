import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { DinoArtwork } from '@/components/art/DinoArtwork'
import { DinoAvatar } from '@/components/DinoAvatar'
import { DINO_AVATARS } from '@/components/dinoAvatars'

describe('shared dinosaur artwork', () => {
  it.each(DINO_AVATARS)('uses the same character for $name in avatars and scenery', ({ key }) => {
    const avatar = render(<DinoAvatar avatarKey={key} />)
    const scene = render(<DinoArtwork avatarKey={key} />)
    expect(avatar.container.querySelector('image')!.getAttribute('href')).toBe(
      scene.container.querySelector('image')!.getAttribute('href'),
    )
    expect(avatar.container.querySelector('svg')!.getAttribute('viewBox')).toBe(
      scene.container.querySelector('svg')!.getAttribute('viewBox'),
    )
    expect(scene.container.querySelector('image')!.getAttribute('href')).toContain('dinosaurs.png')
    expect(avatar.container.querySelector('clipPath')!.id).not.toBe(
      scene.container.querySelector('clipPath')!.id,
    )
  })
  it('assigns a distinct region to each dinosaur', () => {
    const regions = DINO_AVATARS.map(({ key }) =>
      render(<DinoArtwork avatarKey={key} />)
        .container.querySelector('svg')!
        .getAttribute('viewBox'),
    )
    expect(new Set(regions).size).toBe(6)
  })
})
