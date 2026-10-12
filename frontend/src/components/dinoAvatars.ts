export type DinoAvatarKey =
  'dino-rex' | 'dino-tricera' | 'dino-ptera' | 'dino-stego' | 'dino-mite' | 'dino-lizard'

export interface DinoAvatarMeta {
  key: DinoAvatarKey
  name: string
  label: string
  color: string
  bgClass: string
}

export const DINO_AVATARS: DinoAvatarMeta[] = [
  {
    key: 'dino-rex',
    name: 'Rex',
    label: 'Rex (T-Rex)',
    color: '#22C55E',
    bgClass: 'bg-emerald-100 dark:bg-emerald-950/60',
  },
  {
    key: 'dino-tricera',
    name: 'Tricera',
    label: 'Tricera (Triceratops)',
    color: '#F97316',
    bgClass: 'bg-orange-100 dark:bg-orange-950/60',
  },
  {
    key: 'dino-ptera',
    name: 'Ptera',
    label: 'Ptera (Pterodactyl)',
    color: '#A855F7',
    bgClass: 'bg-purple-100 dark:bg-purple-950/60',
  },
  {
    key: 'dino-stego',
    name: 'Stego',
    label: 'Stego (Stegosaurus)',
    color: '#06B6D4',
    bgClass: 'bg-cyan-100 dark:bg-cyan-950/60',
  },
  {
    key: 'dino-mite',
    name: 'DinoMite',
    label: 'DinoMite (Baby Raptor)',
    color: '#84CC16',
    bgClass: 'bg-lime-100 dark:bg-lime-950/60',
  },
  {
    key: 'dino-lizard',
    name: 'LavaLizard',
    label: 'LavaLizard (Volcanic Lizard)',
    color: '#EF4444',
    bgClass: 'bg-rose-100 dark:bg-rose-950/60',
  },
]

export const DEFAULT_AVATAR_KEY: DinoAvatarKey = 'dino-rex'
